#!/usr/bin/env python3
"""Fit a coordinated policy from recorded episodes and write the model with its report."""
import argparse
import collections
import json
import subprocess
from pathlib import Path

from . import dataset, fqi, runtime


def greedy_summary(policy, roots):
    """What the fitted policy would choose at every recorded decision point."""
    reasons, moves, agreement, points = collections.Counter(), collections.Counter(), 0, 0
    for root in roots:
        for directory in dataset.find_episodes(root):
            metadata, rows = dataset.read_episode(directory)
            for point in dataset.decision_points(metadata, rows, policy["resources"]):
                chosen, _, reason = runtime.decide(policy, point)
                reasons[reason] += 1
                points += 1
                if chosen is not None:
                    moves[",".join(str((t > c) - (t < c)) for c, t in zip(point["capacities"], chosen))] += 1
                    agreement += chosen == point["chosen"]
    return {"decisionPoints": points, "reasons": dict(reasons), "directions": dict(sorted(moves.items())),
            "sameAsRecorded": agreement}


def source_version():
    repo = Path(__file__).resolve().parents[2]
    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True).strip())
        return {"commit": commit, "dirty": dirty}
    except (OSError, subprocess.CalledProcessError):
        return {"commit": "unknown", "dirty": True}


def train(train_roots, validation_roots, output, **overrides):
    samples = [s for root in train_roots for s in dataset.load(root)]
    held_out = [s for root in validation_roots for s in dataset.load(root)]
    if not samples:
        raise ValueError("no training transitions found")
    model, counts, config, trace = fqi.fit(samples, **overrides)
    provenance = {"source": source_version(), "trainTransitions": len(samples),
                  "trainEpisodes": len({s["episode"] for s in samples})}
    policy = fqi.export(model, counts, config, provenance=provenance)
    text = fqi.dumps(policy)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "policy.json").write_text(text)
    rewards = [s["reward"] for s in samples]
    report = {"reportVersion": 1, "policySha256": fqi.sha256(text), "config": config, "provenance": provenance,
              "train": {"transitions": len(samples), "meanReward": sum(rewards) / len(rewards),
                        "bellmanRmse": fqi.bellman_error(model, samples, counts, config),
                        "byMode": dict(collections.Counter(s["mode"] for s in samples)),
                        "byScenario": dict(collections.Counter(s["scenario"] for s in samples)),
                        "greedy": greedy_summary(policy, train_roots)},
              "support": counts, "trace": trace,
              "featureImportance": dict(sorted(zip(policy["stateFeatures"] + policy["actionFeatures"],
                                                   (round(float(v), 5) for v in getattr(model, "advantage", model).feature_importances_)),
                                               key=lambda item: -item[1])),
              "policyPerformanceEstablished": False}
    if held_out:
        report["validation"] = {"transitions": len(held_out),
                                "meanReward": sum(s["reward"] for s in held_out) / len(held_out),
                                "bellmanRmse": fqi.bellman_error(model, held_out, counts, config),
                                "greedy": greedy_summary(policy, validation_roots)}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return policy, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--train", type=Path, nargs="+", required=True)
    parser.add_argument("--validation", type=Path, nargs="*", default=[])
    parser.add_argument("--out", type=Path, required=True)
    for name, kind in (("iterations", int), ("trees", int), ("min-leaf", int), ("max-features", float),
                       ("gamma-per-second", float), ("seed", int), ("min-support", int), ("margin", float),
                       ("confidence", float), ("decomposition", str)):
        parser.add_argument(f"--{name}", type=kind)
    args = parser.parse_args()
    keys = {"iterations": "iterations", "trees": "trees", "min_leaf": "minLeaf", "max_features": "maxFeatures",
            "gamma_per_second": "gammaPerSecond", "seed": "seed", "min_support": "minSupport", "margin": "margin",
            "confidence": "confidence", "decomposition": "decomposition"}
    overrides = {keys[k]: v for k, v in vars(args).items() if k in keys and v is not None}
    _, report = train(args.train, args.validation, args.out, **overrides)
    print(json.dumps({k: report[k] for k in ("policySha256", "train", "validation") if k in report}, indent=2))


if __name__ == "__main__":
    main()
