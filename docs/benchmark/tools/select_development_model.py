#!/usr/bin/env python3
"""Bounded, predeclared FQI comparison on an unchanged validation partition; never reads test."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from train_fqi import train
from validate_episodes import require

GRID = ({"id": "reference", "depth": 6, "max_features": 1.0},
        {"id": "deeper", "depth": 10, "max_features": 1.0},
        {"id": "subspace", "depth": 10, "max_features": 0.25})


def select(directory, output):
    directory, output = Path(directory), Path(output)
    require(not output.exists(), "selection output already exists")
    output.mkdir(parents=True)
    candidates = []
    for spec in GRID:
        destination = output / spec["id"]
        model, report = train(directory, destination, depth=spec["depth"], max_features=spec["max_features"], development_only=True)
        metric = report["trace"][-1]["validation"]
        rmse = metric["bellmanRmse"]
        valid = metric["scored"] > 0 and type(rmse) in (int, float) and math.isfinite(rmse)
        candidates.append({"id": spec["id"], "config": report["config"], "validationScored": metric["scored"],
                           "validationBellmanRmse": rmse if valid else None, "eligible": valid,
                           "modelSha256": hashlib.sha256((destination / "model.json").read_bytes()).hexdigest(),
                           "inputSha256": model["inputSha256"]})
    # No coverage changes between models; otherwise conditional diagnostics would not be comparable.
    require(len({c["validationScored"] for c in candidates}) == 1
            and all(c["inputSha256"] == candidates[0]["inputSha256"] for c in candidates),
            "candidates used different validation coverage or inputs")
    eligible = [c for c in candidates if c["eligible"]]
    selected = min(eligible, key=lambda c: (c["validationBellmanRmse"], [s["id"] for s in GRID].index(c["id"]))) if eligible else None
    result = {"selectionVersion": 1, "criterion": "minimum_final_conditional_validation_bellman_rmse",
              "tieBreak": "predeclared_grid_order", "selected": selected["id"] if selected else None,
              "purpose": "development_diagnostic_candidate_for_technical_checks_only", "candidates": candidates,
              "testEvaluated": False, "policyPerformanceEstablished": False,
              "limitation": "Bellman residual is model-dependent and conditional on support; it is not a policy value estimate"}
    (output / "selection.json").write_text(json.dumps(result, sort_keys=True, indent=2, allow_nan=False) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prepared", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = select(args.prepared, args.out)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Development selection failed: {error}\n")
    print(json.dumps({"selected": result["selected"], "testEvaluated": False}))
