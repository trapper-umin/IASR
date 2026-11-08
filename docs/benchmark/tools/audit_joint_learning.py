#!/usr/bin/env python3
"""Audit retained joint-action coverage and Q sensitivity using train/validation only."""
import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

from fqi_features import fit_preprocessor, transform, action_features
from fqi_runtime import load_model, supported_actions, predict_forest, decide, action_key
from policy_data import state_bin, LIMIT, POOL
from train_fqi import load_partitions, close_training_support, joint_readiness
from validate_episodes import require


def lag_descriptions(records, horizons=(1, 3, 5)):
    """Observed trajectories only. Intervening changes are counted, never called causal effects."""
    indexed = {(r["runId"], r["episodeId"], r["sequence"]): r for r in records}
    groups = defaultdict(lambda: {"count": 0, "limitChangedDuringFollowup": 0,
                                  "goodputSum": 0.0, "p95Sum": 0.0, "simultaneousLimitChangeAtStart": 0})
    excluded = Counter()
    for row in records:
        if row["action"][1] == 0:
            continue
        for horizon in horizons:
            future = [indexed.get((row["runId"], row["episodeId"], row["sequence"] + i)) for i in range(horizon)]
            if any(r is None for r in future):
                excluded[f"h{horizon}:gap_or_episode_end"] += 1
                continue
            if any(r["action"][1] != 0 for r in future[1:]):
                excluded[f"h{horizon}:another_pool_change"] += 1
                continue
            outcome = future[-1]["nextHistory"][-1]["state"]
            gp, p95 = outcome.get("goodput_rps"), outcome.get("latency_p95_ms")
            if gp is None or p95 is None:
                excluded[f"h{horizon}:missing_outcome"] += 1
                continue
            key = f"{state_bin(row)}/dp={row['action'][1]}/h={horizon}"
            group = groups[key]
            group["count"] += 1
            group["simultaneousLimitChangeAtStart"] += int(row["action"][0] != 0)
            group["limitChangedDuringFollowup"] += int(any(r["action"][0] != 0 for r in future[1:]))
            group["goodputSum"] += gp
            group["p95Sum"] += p95
    return {"scope": "descriptive_observed_trajectories_not_causal_action_effects",
            "groups": {key: {"count": value["count"], "simultaneousLimitChangeAtStart": value["simultaneousLimitChangeAtStart"], "limitChangedDuringFollowup": value["limitChangedDuringFollowup"],
                              "meanGoodputRps": value["goodputSum"] / value["count"],
                              "meanP95Ms": value["p95Sum"] / value["count"]} for key, value in sorted(groups.items())},
            "excluded": dict(sorted(excluded.items()))}


def sensitivity(records, model):
    chosen, pool_spreads, limit_spreads = Counter(), [], []
    pool_opportunities = simultaneous_opportunities = fallbacks = 0
    for row in records:
        options = supported_actions(row["history"], row["context"], model["preprocessing"], model["support"])
        pool_opportunities += int(any(a[1] != 0 for a in options))
        simultaneous_opportunities += int(any(a[0] != 0 and a[1] != 0 for a in options))
        decision = decide(model, row["history"], row["context"])
        if decision["fallback"]:
            fallbacks += 1
            continue
        chosen[action_key(decision["action"])] += 1
        features = transform(row["history"], row["context"], model["preprocessing"])
        scored = [(a, predict_forest(model["forest"], action_features(features, a, row["context"]))) for a in options]
        for fixed, destination in ((0, pool_spreads), (1, limit_spreads)):
            buckets = defaultdict(list)
            for action, q in scored:
                buckets[action[fixed]].append(q)
            differences = [max(values) - min(values) for values in buckets.values() if len(values) > 1]
            if differences:
                destination.append(max(differences))
    def summarize(values):
        return {"historiesWithComparableActions": len(values), "nonzeroQSpreadHistories": sum(v > 1e-12 for v in values),
                "maxQSpread": max(values, default=None)}
    return {"histories": len(records), "fallbacks": fallbacks, "decisions": dict(sorted(chosen.items())),
            "supportedPoolOpportunities": pool_opportunities, "supportedSimultaneousOpportunities": simultaneous_opportunities,
            "poolSensitivityAtFixedLimitAction": summarize(pool_spreads),
            "limitSensitivityAtFixedPoolAction": summarize(limit_spreads)}


def action_node_coverage(partitions, model):
    """Where action-dependent splits can actually be reached by supported candidates."""
    targets = {}
    names = [*model["preprocessing"]["featureNames"], "action.limit", "action.pool"]
    for ti, tree in enumerate(model["forest"]):
        parents = {}
        for node, left in enumerate(tree["left"]):
            if left < 0:
                continue
            require(0 <= tree["feature"][node] < 210, "invalid tree feature")
            for child, direction in ((left, "<="), (tree["right"][node], ">")):
                require(node < child < len(tree["left"]) and child not in parents, "invalid tree edge")
                parents[child] = (node, direction)
        for node, feature in enumerate(tree["feature"]):
            if feature not in (208, 209):
                continue
            ancestors, cursor = [], node
            while cursor:
                require(cursor in parents, "unreachable action node")
                parent, direction = parents[cursor]
                ancestors.append({"feature": names[tree["feature"][parent]], "operator": direction,
                                  "threshold": tree["threshold"][parent]})
                cursor = parent
            targets[(ti, node)] = {"tree": ti, "node": node, "actionFeature": names[feature],
                                   "threshold": tree["threshold"][node], "path": list(reversed(ancestors)),
                                   "partitions": {}}
    for split, records in partitions.items():
        counts, branches = Counter(), Counter()
        for row in records:
            options = supported_actions(row["history"], row["context"], model["preprocessing"], model["support"])
            state = transform(row["history"], row["context"], model["preprocessing"])
            visited = defaultdict(set)
            for action in options:
                values = action_features(state, action, row["context"])
                for ti in {key[0] for key in targets}:
                    tree, node = model["forest"][ti], 0
                    while tree["left"][node] >= 0:
                        take_left = values[tree["feature"][node]] <= tree["threshold"][node]
                        if (ti, node) in targets:
                            visited[(ti, node)].add(take_left)
                        node = tree["left"][node] if take_left else tree["right"][node]
            for key, directions in visited.items():
                counts[key] += 1
                branches[key] += int(len(directions) == 2)
        for key, target in targets.items():
            target["partitions"][split] = {"historiesReachingNode": counts[key],
                                           "historiesWithSupportedCandidatesOnBothBranches": branches[key]}
    return {"thresholdSpace": "normalized_float32_features", "nodes": list(targets.values())}


def audit(directory, model_path=None, min_samples=3, min_episodes=2, max_distance=2.0):
    directory = Path(directory)
    # Intentionally never open test.jsonl; even outcome diagnostics are development-only.
    manifest, parts = load_partitions(directory, splits=("train", "validation"))
    prep = fit_preprocessor(parts["train"])
    closure_trace, closure_failure = [], None
    try:
        support, cached, excluded = close_training_support(parts["train"], prep, min_samples, min_episodes, max_distance, trace=closure_trace)
    except ValueError as error:
        if "support closure removed every transition" not in str(error):
            raise
        cached, excluded, closure_failure = [], {"support_closure_exhausted": len(parts["train"])}, str(error)
    retained = [item["record"] for item in cached]
    counts = Counter(tuple(r["action"]) for r in parts["train"])
    final = Counter(tuple(r["action"]) for r in retained)
    report = {"auditVersion": 1, "partitionsRead": ["train", "validation"], "testEvaluated": False,
              "inputSha256": {name: hashlib.sha256((directory / (name + ".jsonl")).read_bytes()).hexdigest() for name in parts},
              "supportParameters": {"minSamples": min_samples, "minEpisodes": min_episodes, "maxRmsDistance": max_distance},
              "train": {"beforeClosure": joint_readiness(parts["train"]), "afterClosure": joint_readiness(retained),
                        "input": len(parts["train"]), "retained": len(retained), "excluded": excluded,
                        "closureTrace": closure_trace, "closureFailure": closure_failure,
                        "removedActions": {action_key(key): counts[key] - final[key] for key in sorted(counts) if counts[key] > final[key]},
                        "lagDescriptions": lag_descriptions(parts["train"])},
              "necessaryJointCoverageEstablished": joint_readiness(retained)["established"],
              "performanceEstablished": False}
    if model_path is not None:
        model_path = Path(model_path)
        model = load_model(model_path)
        require(all(model["inputSha256"][name + ".jsonl"] == digest for name, digest in report["inputSha256"].items()),
                "model was fitted/reported on different development data")
        report["modelSha256"] = hashlib.sha256(model_path.read_bytes()).hexdigest()
        report["actionSplitNodes"] = {name: sum(f == index for tree in model["forest"] for f in tree["feature"])
                                      for name, index in (("concurrency", 208), ("pool", 209))}
        report["modelDiagnostics"] = {name: sensitivity(rows, model) for name, rows in parts.items()}
        report["actionNodeCoverage"] = action_node_coverage(parts, model)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prepared", type=Path)
    parser.add_argument("--model", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = audit(args.prepared, args.model)
        with args.out.open("x") as output:
            output.write(json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Joint learning audit failed: {error}\n")
    print(json.dumps({"necessaryJointCoverageEstablished": report["necessaryJointCoverageEstablished"],
                      "retained": report["train"]["retained"], "testEvaluated": False}))
