"""Generate independent Python golden cases for the JVM runtime from prepared data."""
import argparse
import copy
import json
from pathlib import Path
from fqi_features import transform
from fqi_runtime import decide, load_model
from policy_data import candidates


def export(model_path, prepared, output):
    model = load_model(model_path)
    manifest = json.loads((prepared / "manifest.json").read_text())
    splits = ("train", "validation") if manifest.get("developmentOnly") or model.get("trainingConfig", {}).get("testEvaluated") is False else ("train", "validation", "test")
    rows = [json.loads(line) for name in splits
            for line in (prepared / (name + ".jsonl")).read_text().splitlines()]
    # Evenly sample all three partitions; include explicit missing and out-of-support cases.
    selected = [rows[i] for i in range(0, len(rows), max(1, len(rows) // 64))]
    missing, distant = copy.deepcopy(rows[0]), copy.deepcopy(rows[0])
    for observation in missing["history"]:
        observation["state"]["cpu_proc"] = None
        observation["state"]["latency_p99_ms"] = None
    for observation in distant["history"]:
        observation["state"]["goodput_rps"] = 1e8
    selected.extend([missing, distant])
    result = []
    for row in selected:
        result.append({"history": row["history"], "context": row["context"],
                       "features": transform(row["history"], row["context"], model["preprocessing"]),
                       "candidates": candidates(row["history"][-1], row["context"]),
                       "decision": decide(model, row["history"], row["context"])})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, separators=(",", ":"), allow_nan=False) + "\n")
    print(f"Exported {len(result)} JVM parity cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("prepared", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.model, args.prepared, args.output)
