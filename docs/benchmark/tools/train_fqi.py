#!/usr/bin/env python3
"""Train an offline, non-deployable FQI prototype; export finite JSON trees, never pickle."""
import argparse
import hashlib
import json
import math
import subprocess
from collections import defaultdict
from pathlib import Path

from fqi_features import (REWARD, fit_preprocessor, transform, action_features, reward, number)
from fqi_runtime import supported_actions, action_key, bin_for, predict_forest
from policy_data import candidates, LIMIT, POOL
from validate_episodes import read_json, require


def load_partitions(directory):
    directory = Path(directory)
    manifest = read_json(directory / "manifest.json")
    require(manifest.get("preparationVersion") == 1, "unsupported prepared dataset")
    require(not manifest.get("prototypeAllowsDirtySource"), "training requires data collected from committed source")
    for key, group in manifest["groups"].items():
        encoded = json.dumps(group["conditions"], sort_keys=True, separators=(",", ":"), allow_nan=False)
        require(hashlib.sha256(encoded.encode()).hexdigest() == key, "condition group hash mismatch")
    episodes = {}
    for episode in manifest["episodes"]:
        identity = (episode["runId"], episode["episodeId"])
        require(identity not in episodes, "episode assigned more than once")
        require(episode["source"].get("dirty") is False
                and episode["source"].get("commit") not in (None, "unknown"), "episode source is not reproducible")
        require(manifest["groups"][episode["groupId"]]["split"] == episode["split"], "episode crossed partition boundary")
        episodes[identity] = episode
    partitions, seen = {}, set()
    for split in ("train", "validation", "test"):
        records = [json.loads(line) for line in (directory / f"{split}.jsonl").read_text().splitlines()]
        require(records, f"empty {split}")
        require(len(records) == manifest["sampleCounts"][split], "sample count differs from manifest")
        for row in records:
            require(row.get("sampleVersion") == 1, "unsupported sample")
            require(manifest["groups"][row["groupId"]]["split"] == split, "group crossed partition boundary")
            episode = episodes[(row["runId"], row["episodeId"])]
            require(episode["groupId"] == row["groupId"] and episode["split"] == split, "sample crossed episode partition")
            identity = (row["runId"], row["episodeId"], row["sequence"])
            require(identity not in seen, "duplicate sample identity")
            seen.add(identity)
            require(len(row["history"]) == len(row["nextHistory"]) == 4, "invalid history length")
            control = row["context"]
            require(control["actionSpaceVersion"] == 1 and type(control["limitStep"]) is int and control["limitStep"] > 0,
                    "unsupported action contract")
            require(row["history"][1:] == row["nextHistory"][:-1], "history does not shift by one observation")
            require(row["candidateActions"] == candidates(row["history"][-1], row["context"]), "invalid action mask")
            require(row["nextCandidateActions"] == candidates(row["nextHistory"][-1], row["context"]), "invalid next mask")
            require(row["action"] in row["candidateActions"], "observed action is not admissible")
            before = row["history"][-1]["resourcesBefore"]
            after = row["nextHistory"][-1]["resourcesBefore"]
            require([after[k] - before[k] for k in (LIMIT, POOL)] == row["action"], "applied action differs from next resources")
            require(number(row["elapsedSeconds"]) > 0, "invalid discount interval")
            require(abs(row["elapsedSeconds"] - row["nextHistory"][-1]["elapsedSeconds"]) <= 1e-6,
                    "discount interval differs from next observation")
        partitions[split] = records
    return manifest, partitions


def build_support(train, preprocessing, min_samples, min_episodes, max_distance):
    bins = defaultdict(lambda: defaultdict(lambda: {"samples": 0, "episodeSet": set(), "vectors": []}))
    for row in train:
        entry = bins[bin_for(row["history"], row["context"])][action_key(row["action"])]
        entry["samples"] += 1
        entry["episodeSet"].add((row["runId"], row["episodeId"]))
        entry["vectors"].append(transform(row["history"], row["context"], preprocessing))
    result = {}
    for label, actions in bins.items():
        result[label] = {key: {"samples": value["samples"], "episodes": len(value["episodeSet"]),
                               "vectors": value["vectors"]} for key, value in actions.items()}
    return {"fittedOn": "train_only", "scope": "coarse_bin_and_action_neighbor_distance",
            "minSamples": min_samples, "minEpisodes": min_episodes, "maxRmsDistance": max_distance,
            "bins": result}


def cache_records(records, preprocessing, support):
    usable, excluded = [], defaultdict(int)
    for row in records:
        try:
            score = reward(row, preprocessing)
        except ValueError:
            excluded["unavailable_reward"] += 1
            continue
        current = supported_actions(row["history"], row["context"], preprocessing, support, row["candidateActions"])
        following = supported_actions(row["nextHistory"], row["context"], preprocessing, support, row["nextCandidateActions"])
        if row["action"] not in current:
            excluded["unsupported_behavior_action"] += 1
            continue
        if not following:
            # Unknown continuation is not a terminal state. Do not insert reward-only targets.
            excluded["unsupported_continuation"] += 1
            continue
        x = transform(row["history"], row["context"], preprocessing)
        nx = transform(row["nextHistory"], row["context"], preprocessing)
        usable.append({"record": row, "reward": score, "x": action_features(x, row["action"], row["context"]),
                       "options": [action_features(x, a, row["context"]) for a in current],
                       "nextOptions": [action_features(nx, a, row["context"]) for a in following]})
    return usable, dict(excluded)


def bellman_targets(cached, previous, gamma):
    import numpy as np
    if previous is None:
        future = [0.0] * len(cached)  # Q_0, not an assertion that the system terminates.
    else:
        options = [x for item in cached for x in item["nextOptions"]]
        predicted = previous.predict(np.asarray(options, dtype=np.float32))
        future, offset = [], 0
        for item in cached:
            count = len(item["nextOptions"])
            future.append(float(max(predicted[offset:offset + count])))
            offset += count
    return [item["reward"] + gamma ** item["record"]["elapsedSeconds"] * value for item, value in zip(cached, future)]


def diagnostics(cached, model, gamma):
    import numpy as np
    if not cached:
        return {"scored": 0, "bellmanRmse": None}
    predicted = model.predict(np.asarray([r["x"] for r in cached], dtype=np.float32))
    targets = bellman_targets(cached, model, gamma)
    return {"scored": len(cached), "bellmanRmse": math.sqrt(sum((a-b)**2 for a, b in zip(predicted, targets)) / len(cached)),
            "meanObservedBehaviorReward": sum(r["reward"] for r in cached) / len(cached)}


def export_forest(model):
    return [{"left": estimator.tree_.children_left.tolist(), "right": estimator.tree_.children_right.tolist(),
             "feature": estimator.tree_.feature.tolist(), "threshold": estimator.tree_.threshold.tolist(),
             "value": estimator.tree_.value[:, 0, 0].tolist()} for estimator in model.estimators_]


def source_version():
    root = Path(__file__).resolve().parents[3]
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()
    try:
        return {"commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain"))}
    except (OSError, subprocess.CalledProcessError):
        return {"commit": "unknown", "dirty": True}


def train(directory, output, iterations=8, trees=32, depth=6, leaf=3, seed=42,
          gamma=0.95, min_samples=3, min_episodes=2, max_distance=2.0):
    import numpy as np
    import sklearn
    import scipy
    import joblib
    import threadpoolctl
    import sys
    from sklearn.ensemble import ExtraTreesRegressor
    require(all(type(v) is int and v > 0 for v in (iterations, trees, depth, leaf, min_samples, min_episodes)),
            "iteration/tree/support counts must be positive integers")
    require(math.isfinite(gamma) and 0 < gamma < 1 and math.isfinite(max_distance) and max_distance > 0, "invalid discount/support radius")
    directory, output = Path(directory), Path(output)
    require(not output.exists(), "model output already exists")
    manifest, partitions = load_partitions(directory)
    train_rows = partitions["train"]
    require(any(r["action"][1] > 0 for r in train_rows) and any(r["action"][1] < 0 for r in train_rows)
            and any(r["action"][0] != 0 and r["action"][1] != 0 for r in train_rows),
            "train lacks observed pool growth/shrinkage and joint actions; collect more data")
    preprocessing = fit_preprocessor(train_rows)
    support = build_support(train_rows, preprocessing, min_samples, min_episodes, max_distance)
    # Test is validated structurally above, but no test metrics/statistics enter fitting or iteration selection.
    train_cache, train_excluded = cache_records(train_rows, preprocessing, support)
    validation_cache, validation_excluded = cache_records(partitions["validation"], preprocessing, support)
    require(len(train_cache) >= max(4, 2 * leaf), "too few supported Bellman transitions; expand coverage")
    x = np.asarray([item["x"] for item in train_cache], dtype=np.float32)
    model, trace = None, []
    for iteration in range(iterations):
        targets = bellman_targets(train_cache, model, gamma)
        fitted = ExtraTreesRegressor(n_estimators=trees, max_depth=depth, min_samples_leaf=leaf,
                                     random_state=seed + iteration, n_jobs=1)
        fitted.fit(x, np.asarray(targets, dtype=np.float64))
        model = fitted
        trace.append({"iteration": iteration + 1, "train": diagnostics(train_cache, model, gamma),
                      "validation": diagnostics(validation_cache, model, gamma)})
    forest = export_forest(model)
    expected = model.predict(x)
    export_error = max(abs(predict_forest(forest, row.tolist()) - expected[i]) for i, row in enumerate(x))
    require(export_error <= 1e-10, "portable export does not match sklearn")
    test_cache, test_excluded = cache_records(partitions["test"], preprocessing, support)
    config = {"iterations": iterations, "trees": trees, "depth": depth, "minLeaf": leaf, "seed": seed,
              "gammaPerSecond": gamma, "iterationSelection": "fixed_budget_no_test_selection", "nJobs": 1}
    digests = {name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
               for name in ("manifest.json", "train.jsonl", "validation.jsonl", "test.jsonl")}
    artifact = {"modelVersion": 1, "algorithm": "masked_fqi_extra_trees", "deploymentAllowed": False,
                "preprocessing": preprocessing, "support": support, "forest": forest,
                "rewardWeights": REWARD, "trainingConfig": config, "inputSha256": digests,
                "trainingSource": source_version(), "versions": {"sklearn": sklearn.__version__, "numpy": np.__version__,
                    "scipy": scipy.__version__, "joblib": joblib.__version__, "threadpoolctl": threadpoolctl.__version__,
                    "python": sys.version.split()[0]},
                "inputGroups": {key: item["split"] for key, item in manifest["groups"].items()}}
    report = {"reportVersion": 1, "config": config, "trace": trace,
              "diagnosticOnly": True, "policyPerformanceEstablished": False,
              "selectionBiasNote": "conditional diagnostics exclude unsupported states/actions; not an off-policy value estimate",
              "counts": {split: {"input": len(partitions[split]), "scored": len(cache), "excluded": excluded}
                         for split, cache, excluded in (("train", train_cache, train_excluded),
                                                       ("validation", validation_cache, validation_excluded),
                                                       ("test", test_cache, test_excluded))},
              "test": diagnostics(test_cache, model, gamma), "portableExportMaxError": float(export_error)}
    output.mkdir(parents=True, exist_ok=False)
    (output / "model.json").write_text(json.dumps(artifact, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n")
    (output / "report.json").write_text(json.dumps(report, sort_keys=True, indent=2, allow_nan=False) + "\n")
    golden = [{"features": row.tolist(), "prediction": float(value)} for row, value in zip(x[:16], expected[:16])]
    (output / "prediction-fixtures.json").write_text(json.dumps(golden, sort_keys=True, allow_nan=False) + "\n")
    return artifact, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--iterations", type=int, default=8)
    parser.add_argument("--trees", type=int, default=32)
    parser.add_argument("--depth", type=int, default=6)
    parser.add_argument("--leaf", type=int, default=3)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    try:
        _, report = train(args.dataset, args.out, args.iterations, args.trees, args.depth, args.leaf, args.seed)
    except (ValueError, KeyError, TypeError, OSError, ImportError) as error:
        parser.exit(1, f"FQI training failed: {error}\n")
    print(json.dumps({"counts": report["counts"], "portableExportMaxError": report["portableExportMaxError"]}, indent=2))


if __name__ == "__main__":
    main()
