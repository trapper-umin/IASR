"""Replay logged JVM prototype decisions with the portable Python model, including warmup."""
import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path
from fqi_runtime import decide, load_model
from policy_data import context, observation, LIMIT, POOL
from validate_episodes import read_json, require, validate_collection


def replay(directory, model_path):
    integrity = validate_collection(directory)
    model, digest = load_model(model_path), hashlib.sha256(model_path.read_bytes()).hexdigest()
    results = []
    for path in sorted(directory.rglob("metadata.json")):
        metadata = read_json(path)
        if metadata["mode"].removesuffix("-pinned") != "learned":
            continue
        control, history, previous = context(metadata), [], -1
        require(metadata["configuration"]["learned"]["modelSha256"] == digest, "model SHA differs from JVM run")
        counts = Counter()
        for line in (path.parent / "dataset.jsonl").read_text().splitlines():
            row = json.loads(line)
            reason = row["reasons"][LIMIT].split(":")[0].split("|")[0]
            counts[reason] += 1
            elapsed = row["state"].get("observation_elapsed_sec")
            tick = row["state"].get("control_tick")
            valid = (type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed > 0
                     and abs(elapsed - control["windowSec"]) <= max(0.1, 0.25 * control["windowSec"])
                     and type(tick) in (int, float) and math.isfinite(tick) and tick >= 0 and tick == int(tick)
                     and row["state"].get("control_window_sec") == control["windowSec"])
            if not valid:
                history, previous = [], -1
                require(reason == "learned_fallback_invalid_observation", "invalid observation used by JVM")
                continue
            if tick != previous + 1:
                history = []
            previous = tick
            history = (history + [observation(row)])[-4:]
            if len(history) < 4:
                counts["unreplayable_initial_history"] += 1
                continue
            expected = decide(model, history, control)
            if reason == "learned_policy":
                require(not expected["fallback"], "JVM model decision outside Python support")
                requested = [row["intent"][key] - row["resourcesBefore"][key] for key in (LIMIT, POOL)]
                require(requested == expected["action"], f"JVM/Python decision differs at {path.parent.name}:{row['sequence']}")
                require(row["intent"] == row["action"], "model proposal changed at enforcement/application")
                counts["matched_model_decisions"] += 1
            elif reason == "learned_fallback_no_empirical_support":
                require(expected["fallback"], "JVM support fallback differs from Python")
                counts["matched_support_fallbacks"] += 1
            else:
                require(reason in ("learned_fallback_budget", "learned_fallback_invalid_action", "learned_fallback_model_error"),
                        f"unexpected fallback with full history: {reason}")
        results.append({"scenario": metadata["scenario"], "episodeId": metadata["episodeId"],
                        "counts": dict(counts), "inference": metadata["inference"]})
    require(results, "no learned episodes")
    return {"modelSha256": digest, "integrity": integrity, "replay": results,
            "performanceEstablished": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("model", type=Path)
    args = parser.parse_args()
    try:
        report = replay(args.directory, args.model)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"JVM replay failed: {error}\n")
    print(json.dumps(report, indent=2))
