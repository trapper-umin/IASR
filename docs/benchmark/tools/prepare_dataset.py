#!/usr/bin/env python3
"""Group complete experiments, build four-window samples and audit train action support."""
import argparse
import hashlib
import json
import random
from collections import Counter, defaultdict
from pathlib import Path

from validate_episodes import read_json, require, validate_collection
from policy_data import samples, state_bin, METRICS


def group_id(metadata):
    conditions = metadata["configuration"].get("conditions")
    require(isinstance(conditions, dict) and conditions.get("profile", {}).get("name"),
            "object conditions/profile are missing; recollect using the current benchmark")
    canonical = json.dumps(conditions, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def partition_groups(groups, seed, test_scenarios=()):
    require(len(groups) >= 3, "at least three distinct condition groups are required")
    ids = sorted(groups)
    rng = random.Random(seed)
    rng.shuffle(ids)
    if test_scenarios:
        selected = set(test_scenarios)
        known = {g["profile"]["name"] for g in groups.values()}
        require(selected <= known, f"unknown held-out scenarios: {selected - known}")
        test = [key for key in ids if groups[key]["profile"]["name"] in selected]
        rest = [key for key in ids if key not in test]
        require(test and len(rest) >= 2, "held-out scenarios must leave at least two groups for train/validation")
    else:
        count = max(1, int(len(ids) * 0.2))
        test, rest = ids[:count], ids[count:]
    validation_count = max(1, int(len(ids) * 0.2))
    validation_count = min(validation_count, len(rest) - 1)
    require(validation_count >= 1, "validation partition would be empty")
    validation, train = rest[:validation_count], rest[validation_count:]
    return {key: name for name, keys in (("train", train), ("validation", validation), ("test", test)) for key in keys}


def coverage(records, min_samples=3, min_episodes=2):
    bins = defaultdict(lambda: {"opportunities": Counter(), "observed": Counter(), "episodes": defaultdict(set)})
    missing = Counter()
    all_actions = Counter()
    for record in records:
        bucket = bins[state_bin(record)]
        action = json.dumps(record["action"], separators=(",", ":"))
        identity = (record["runId"], record["episodeId"])
        all_actions[action] += 1
        bucket["observed"][action] += 1
        bucket["episodes"][action].add(identity)
        for candidate in record["candidateActions"]:
            bucket["opportunities"][json.dumps(candidate, separators=(",", ":"))] += 1
        for name in METRICS:
            if record["history"][-1]["state"].get(name) is None:
                missing[name] += 1
    report = {}
    for label, bucket in sorted(bins.items()):
        report[label] = {action: {"opportunities": count, "samples": bucket["observed"][action],
                                 "episodes": len(bucket["episodes"][action]),
                                 "supported": bucket["observed"][action] >= min_samples
                                 and len(bucket["episodes"][action]) >= min_episodes}
                         for action, count in sorted(bucket["opportunities"].items())}
    return {"samples": len(records), "actions": dict(sorted(all_actions.items())), "bins": report,
            "missingLatestMetrics": dict(missing), "minSamples": min_samples, "minEpisodes": min_episodes,
            "readiness": "not_established", "supportScope": "diagnostic_coarse_bins"}


def prepare(root, output, seed=42, test_scenarios=(), allow_dirty=False):
    root, output = Path(root), Path(output)
    for marker in root.rglob("collection.json"):
        collection = read_json(marker)
        require(collection.get("status") == "complete"
                and set(collection.get("completed", [])) == {c["id"] for c in collection["matrix"]["cases"]},
                f"unfinished collection: {marker}")
    validation = validate_collection(root)
    paths = [root] if (root / "metadata.json").is_file() else sorted(p.parent for p in root.rglob("metadata.json"))
    groups, episodes, by_split = {}, [], {key: [] for key in ("train", "validation", "test")}
    for path in paths:
        metadata = read_json(path / "metadata.json")
        if metadata["mode"].removesuffix("-pinned") == "off":
            continue
        require(metadata["source"].get("commit") not in (None, "unknown"), "unknown source revision")
        require(allow_dirty or metadata["source"].get("dirty") is False,
                "uncommitted source: commit before collection, or use --allow-dirty for a prototype")
        key = group_id(metadata)
        groups[key] = metadata["configuration"]["conditions"]
        rows = [json.loads(line) for line in (path / "dataset.jsonl").read_text().splitlines()]
        built, excluded = samples(metadata, rows)
        for sample in built:
            sample.update(runId=metadata["runId"], episodeId=metadata["episodeId"], groupId=key)
        episodes.append((metadata, key, built, excluded))
    assignment = partition_groups(groups, seed, test_scenarios)
    manifest_episodes = []
    for metadata, key, built, excluded in episodes:
        split = assignment[key]
        by_split[split].extend(built)
        manifest_episodes.append({"runId": metadata["runId"], "episodeId": metadata["episodeId"], "groupId": key,
                                  "split": split, "samples": len(built), "excluded": excluded,
                                  "source": metadata["source"]})
    for split, records in by_split.items():
        require(bool(records), f"{split} has no usable four-window samples; collect longer/more varied episodes")
        records.sort(key=lambda r: (r["groupId"], r["runId"], r["episodeId"], r["sequence"]))
    manifest = {"preparationVersion": 1, "splitSeed": seed, "historyWindows": 4,
                "testScenarios": list(test_scenarios), "prototypeAllowsDirtySource": allow_dirty,
                "groups": {key: {"split": assignment[key], "conditions": groups[key]} for key in sorted(groups)},
                "episodes": manifest_episodes, "validation": validation,
                "sampleCounts": {name: len(records) for name, records in by_split.items()}}
    audit = {name: coverage(records) for name, records in by_split.items()}
    output.mkdir(parents=True, exist_ok=False)  # Never replace a previous split/model input.
    for name, records in by_split.items():
        (output / f"{name}.jsonl").write_text("".join(json.dumps(r, sort_keys=True, allow_nan=False) + "\n" for r in records))
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (output / "coverage.json").write_text(json.dumps(audit, indent=2, sort_keys=True) + "\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--test-scenarios", default="", help="comma-separated profiles reserved entirely for test")
    parser.add_argument("--allow-dirty", action="store_true", help="prototype only; manifest records this exception")
    args = parser.parse_args()
    try:
        result = prepare(args.directory, args.out, args.seed,
                         tuple(filter(None, args.test_scenarios.split(","))), args.allow_dirty)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Dataset preparation failed: {error}\n")
    print(json.dumps({"groups": len(result["groups"]), "sampleCounts": result["sampleCounts"]}, indent=2))


if __name__ == "__main__":
    main()
