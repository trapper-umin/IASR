#!/usr/bin/env python3
"""Validate v3 benchmark episodes without third-party packages; never merge episodes."""
import argparse
import json
import math
from datetime import datetime
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def timestamp(value):
    require(isinstance(value, str), "timestamp must be an ISO string")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, "timestamp must include a timezone")
    return parsed


def numeric_map(value, label, integers=False, allow_negative=False):
    require(isinstance(value, dict), f"{label} must be an object")
    for key, number in value.items():
        require(isinstance(key, str), f"{label} has a non-string key")
        if number is None and not integers:
            continue  # An unavailable metric is missing, not zero.
        require(type(number) in (int, float) and math.isfinite(number), f"{label}.{key} is not finite")
        if integers:
            require(type(number) is int and (allow_negative or number >= 0), f"{label}.{key} has an invalid integer")


def invalid_constant(value):
    raise ValueError(f"nonstandard JSON number: {value}")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), parse_constant=invalid_constant)


def validate_episode(directory):
    directory = Path(directory)
    for filename in ("metadata.json", "dataset.jsonl", "result.json", "actuators.csv"):
        require((directory / filename).is_file(), f"missing {filename}")
    metadata = read_json(directory / "metadata.json")
    require(metadata.get("metadataVersion") == 1, "unsupported metadata version")
    require(metadata.get("datasetSchemaVersion") == 3, "expected dataset schema 3")
    require(metadata.get("status") == "complete", "episode is incomplete")
    require(isinstance(metadata.get("source"), dict), "source provenance is missing")
    require(type(metadata.get("orderSeed")) is int, "order seed is missing")
    require(type(metadata.get("repetition")) is int and metadata["repetition"] > 0, "invalid repetition")
    require(isinstance(metadata.get("mode"), str), "mode is missing")
    timing = metadata["timing"]
    start = timestamp(timing["measurementStartedAt"])
    end = timestamp(timing["measurementScheduledEndAt"])
    captured = timestamp(timing["captureEndedAt"])
    require(start < end <= captured, "measurement/capture boundaries are inconsistent")

    rows = []
    for line_number, line in enumerate((directory / "dataset.jsonl").read_text(encoding="utf-8").splitlines(), 1):
        require(bool(line.strip()), f"line {line_number}: empty JSONL record")
        row = json.loads(line, parse_constant=invalid_constant)
        require(isinstance(row, dict) and row.get("schemaVersion") == 3, f"line {line_number}: expected schema 3")
        require(type(row.get("sequence")) is int and row["sequence"] >= 0, "invalid sequence")
        require(type(row.get("windowSec")) in (int, float) and math.isfinite(row["windowSec"])
                and row["windowSec"] > 0, "invalid configured window")
        numeric_map(row.get("state"), "state")
        for key in ("resourcesBefore", "action", "cooldownRemaining"):
            numeric_map(row.get(key), key, integers=True)
        numeric_map(row.get("intent"), "intent", integers=True, allow_negative=True)
        require(row["resourcesBefore"].keys() == row["action"].keys(), "resource keys differ before/after action")
        require(row["cooldownRemaining"].keys() <= row["resourcesBefore"].keys(), "unknown cooldown resource")
        current = timestamp(row["timestamp"])
        require(current <= captured, "record occurs after capture ended")
        window_start = row.get("stateWindowStartedAt")
        if window_start is not None:
            require(timestamp(window_start) < current, "state window must have positive duration")
        if row.get("outcome") is not None:
            numeric_map(row["outcome"], "outcome")
            require(current < timestamp(row.get("outcomeTimestamp")) <= captured, "invalid outcome time")
        else:
            require(row.get("outcomeTimestamp") is None, "null outcome has a timestamp")
        rows.append(row)

    require(len(rows) == metadata.get("datasetRecords"), "record count does not match metadata")
    if metadata["mode"].removesuffix("-pinned") == "off":
        require(not rows, "off mode unexpectedly contains control records")
    else:
        require(len(rows) >= 2, "control episode must contain transitions and a terminal record")
        require(rows[-1]["outcome"] is None, "terminal record was lost before shutdown")

    measurement = irregular = 0
    changes = set()
    for index, row in enumerate(rows):
        if index:
            previous = rows[index - 1]
            require(row["sequence"] == previous["sequence"] + 1, "sequence gap or duplicate")
            require(row["stateWindowStartedAt"] == previous["timestamp"], "state window is not linked to previous observation")
            require(previous["outcomeTimestamp"] == row["timestamp"], "outcome time differs from next observation")
            require(previous["outcome"] == row["state"], "outcome differs from next state")
            require(previous["action"] == row["resourcesBefore"], "resources changed outside the recorded action")
        if row["outcome"] is None:
            require(index == len(rows) - 1, "null outcome before the last record")
            continue
        window_start = row["stateWindowStartedAt"]
        if window_start is None:
            continue  # Bootstrap state has no known observation interval.
        state_seconds = (timestamp(row["timestamp"]) - timestamp(window_start)).total_seconds()
        outcome_seconds = (timestamp(row["outcomeTimestamp"]) - timestamp(row["timestamp"])).total_seconds()
        tolerance = max(0.1, 0.25 * row["windowSec"])
        if any(abs(seconds - row["windowSec"]) > tolerance for seconds in (state_seconds, outcome_seconds)):
            irregular += 1
            continue
        if timestamp(window_start) >= start and timestamp(row["outcomeTimestamp"]) <= end:
            measurement += 1
            changes.add(tuple(sorted((k, row["action"][k] - v) for k, v in row["resourcesBefore"].items())))
    return {"episodeId": metadata["episodeId"], "records": len(rows),
            "measurementTransitions": measurement, "irregularTransitions": irregular,
            "distinctAppliedChanges": len(changes), "sourceDirty": metadata["source"].get("dirty")}


def validate_collection(root):
    root = Path(root)
    episodes = sorted(p.parent for p in root.rglob("metadata.json"))
    if (root / "metadata.json").is_file():
        episodes = [root]
    require(bool(episodes), "no completed episodes found")
    incomplete = [p for p in root.rglob("repeat-*") if p.is_dir() and not (p / "metadata.json").is_file()]
    require(not incomplete, f"incomplete episode directories: {incomplete}")
    results, identities = [], set()
    for episode in episodes:
        try:
            metadata = read_json(episode / "metadata.json")
            identity = (metadata.get("runId"), metadata.get("episodeId"))
            require(identity not in identities, f"duplicate episode identity {identity}")
            identities.add(identity)
            results.append(validate_episode(episode))
        except (ValueError, KeyError, TypeError, OSError) as error:
            raise ValueError(f"{episode}: {error}") from error
    return {"episodes": len(results), "records": sum(r["records"] for r in results),
            "measurementTransitions": sum(r["measurementTransitions"] for r in results), "details": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        report = validate_collection(args.directory)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid episodes: {error}\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
