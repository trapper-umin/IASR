"""Joint-action-conditioned ExtraTrees regression; no manual action preferences."""
from collections import defaultdict
from fqi_runtime import normalized_action_key
from validate_episodes import require


class ActionForestsRegressor:
    def __init__(self, trees, depth, leaf, seed, max_features):
        self.parameters = dict(n_estimators=trees, max_depth=depth, min_samples_leaf=leaf,
                               n_jobs=1, max_features=max_features)
        self.seed = seed
        self.models = {}
        self.counts = {}

    def fit(self, x, targets):
        import numpy as np
        from sklearn.ensemble import ExtraTreesRegressor
        x, targets = np.asarray(x, dtype=np.float32), np.asarray(targets, dtype=np.float64)
        require(x.ndim == 2 and x.shape[1] == 210 and len(x) == len(targets), "invalid action-regression dimensions")
        self.models, self.counts = {}, {}
        require(len(x) > 0, "no action-regression samples")
        buckets = defaultdict(list)
        for index, row in enumerate(x):
            buckets[normalized_action_key(row[-2:].tolist())].append(index)
        for index, (key, selected) in enumerate(sorted(buckets.items())):
            model = ExtraTreesRegressor(**self.parameters, random_state=(self.seed + index * 1009) % (2 ** 32))
            model.fit(x[selected, :208], targets[selected])
            self.models[key] = model
            self.counts[key] = len(selected)
        return self

    def predict(self, x):
        import numpy as np
        x = np.asarray(x, dtype=np.float32)
        require(x.ndim == 2 and x.shape[1] == 210, "invalid action-regression input")
        buckets = defaultdict(list)
        for index, row in enumerate(x):
            key = normalized_action_key(row[-2:].tolist())
            require(key in self.models, "action has no trained forest")
            buckets[key].append(index)
        predictions = np.empty(len(x), dtype=np.float64)
        for key, selected in buckets.items():
            predictions[selected] = self.models[key].predict(x[selected, :208])
        return predictions
