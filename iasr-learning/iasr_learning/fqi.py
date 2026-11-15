"""Fitted Q iteration over afterstate features, and export of the fitted policy."""
import collections
import hashlib
import json

import numpy as np
from sklearn.ensemble import ExtraTreesRegressor

from . import features as F
from .dataset import DEFAULT_RESOURCES, MIN_HISTORY, MOVE_COST

class AdvantageModel:
    """Q(s, a) = V(s) + A(s, a), each an ensemble of trees.

    The value of a state varies far more than the difference between the moves
    available in it. A single regressor spends its splits on the first and
    leaves the second to noise; fitting V on the state alone and A on what V
    leaves unexplained gives the action features the whole residual to explain.
    """

    def __init__(self, value, advantage, state_size):
        self.value, self.advantage, self.state_size = value, advantage, state_size

    def predict(self, x):
        return self.value.predict(x[:, :self.state_size]) + self.advantage.predict(x)


DEFAULTS = {"iterations": 30, "trees": 60, "minLeaf": 10, "maxFeatures": 0.6, "gammaPerSecond": 0.9,
            "seed": 20261115, "minSupport": 8, "margin": 0.0, "confidence": 1.0, "decomposition": "advantage"}


def support_counts(samples):
    counts = collections.defaultdict(collections.Counter)
    for sample in samples:
        counts[sample["bin"]][sample["direction"]] += 1
    return {name: dict(sorted(value.items())) for name, value in sorted(counts.items())}


def supported(counts, bin_name, direction, minimum):
    return counts.get(bin_name, {}).get(direction, 0) >= minimum


class NextIndex:
    """Flattened next-state candidates so that one prediction serves a whole iteration."""

    def __init__(self, samples, counts, minimum):
        rows, self.starts = [], []
        hold = None
        for sample in samples:
            self.starts.append(len(rows))
            kept = 0
            for features, direction in zip(sample["nextCandidates"], sample["nextDirections"]):
                if direction == "*" or supported(counts, sample["nextBin"], direction, minimum):
                    rows.append(sample["nextState"] + features)
                    kept += 1
            if kept == 0:
                # Nothing was tried in such a state: assume the next step holds.
                rows.append(sample["nextState"] + sample["nextCandidates"][0])
        self.matrix = np.asarray(rows, dtype=np.float32)

    def best(self, model):
        if model is None:
            return np.zeros(len(self.starts))
        return np.maximum.reduceat(model.predict(self.matrix), self.starts)


def fit(samples, **overrides):
    config = {**DEFAULTS, **overrides}
    counts = support_counts(samples)
    x = np.asarray([s["state"] + s["action"] for s in samples], dtype=np.float32)
    rewards = np.asarray([s["reward"] for s in samples])
    discount = np.asarray([config["gammaPerSecond"] ** s["elapsed"] for s in samples])
    following = NextIndex(samples, counts, config["minSupport"])
    model, trace = None, []
    for iteration in range(config["iterations"]):
        targets = rewards + discount * following.best(model)
        def forest():
            return ExtraTreesRegressor(n_estimators=config["trees"], min_samples_leaf=config["minLeaf"],
                                       max_features=config["maxFeatures"], random_state=config["seed"], n_jobs=1)
        if config["decomposition"] == "advantage":
            size = len(samples[0]["state"])
            value = forest().fit(x[:, :size], targets)
            advantage = forest().fit(x, targets - value.predict(x[:, :size]))
            model = AdvantageModel(value, advantage, size)
        else:
            model = forest().fit(x, targets)
        residual = model.predict(x) - targets
        trace.append({"iteration": iteration + 1, "meanTarget": float(targets.mean()),
                      "rmse": float(np.sqrt(np.mean(residual ** 2)))})
    return model, counts, config, trace


def bellman_error(model, samples, counts, config):
    """Root mean squared Bellman residual of a fitted model on other transitions."""
    x = np.asarray([s["state"] + s["action"] for s in samples], dtype=np.float32)
    rewards = np.asarray([s["reward"] for s in samples])
    discount = np.asarray([config["gammaPerSecond"] ** s["elapsed"] for s in samples])
    targets = rewards + discount * NextIndex(samples, counts, config["minSupport"]).best(model)
    return float(np.sqrt(np.mean((model.predict(x) - targets) ** 2)))


def export_forest(model):
    return [{"left": e.tree_.children_left.tolist(), "right": e.tree_.children_right.tolist(),
             "feature": e.tree_.feature.tolist(), "threshold": e.tree_.threshold.tolist(),
             "value": e.tree_.value[:, 0, 0].tolist()} for e in model.estimators_]


def export(model, counts, config, resources=DEFAULT_RESOURCES, provenance=None):
    """The policy as plain data: what the JVM runtime loads."""
    return {"modelVersion": 3, "algorithm": "fqi_extra_trees_afterstate", "featureVersion": F.FEATURE_VERSION,
            "smoothing": F.ALPHA, "ratioCap": F.RATIO_CAP, "minHistory": MIN_HISTORY, "moveCost": MOVE_COST,
            "resources": resources,
            "stateFeatures": F.state_feature_names(resources), "actionFeatures": F.action_feature_names(resources),
            "support": {"minSamples": config["minSupport"], "bins": counts},
            "margin": config["margin"], "confidence": config["confidence"], "training": config, "provenance": provenance or {},
            **({"valueForest": export_forest(model.value), "forest": export_forest(model.advantage)}
               if isinstance(model, AdvantageModel) else {"forest": export_forest(model)})}


def dumps(policy):
    return json.dumps(policy, sort_keys=True, separators=(",", ":")) + "\n"


def sha256(text):
    return hashlib.sha256(text.encode()).hexdigest()
