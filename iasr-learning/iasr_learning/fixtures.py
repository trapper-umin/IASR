#!/usr/bin/env python3
"""Export recorded windows with the reference decisions, for the JVM parity test."""
import argparse
import json
from pathlib import Path

from . import dataset, runtime


def export(policy, episode_directory, limit=None):
    metadata, rows = dataset.read_episode(episode_directory)
    resources = policy["resources"]
    names = [resource["actuator"] for resource in resources]
    points = {p["index"]: p for p in dataset.decision_points(metadata, rows, resources)}
    windows = []
    for index, row in enumerate(rows[:limit]):
        window = {"state": row["state"], "capacities": [row["resourcesBefore"].get(n) for n in names],
                  "intent": dataset.proposal(row)}
        point = points.get(index)
        if point is not None:
            chosen, scored, reason = runtime.decide(policy, point)
            window["expected"] = {"features": point["state"], "candidates": point["candidates"],
                                  "reference": point["reference"],
                                  "values": scored, "targets": chosen, "reason": reason}
        windows.append(window)
    return {"fixtureVersion": 1, "sloMs": metadata["configuration"]["sloMs"],
            "guardrails": dataset.episode_rules(metadata, resources), "windows": windows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("policy", type=Path)
    parser.add_argument("episode", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    fixture = export(json.loads(args.policy.read_text()), args.episode, args.limit)
    args.out.write_text(json.dumps(fixture, separators=(",", ":")) + "\n")
    decided = sum("expected" in w for w in fixture["windows"])
    print(f"{len(fixture['windows'])} windows, {decided} decision points")


if __name__ == "__main__":
    main()
