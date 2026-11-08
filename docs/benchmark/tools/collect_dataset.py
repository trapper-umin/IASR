#!/usr/bin/env python3
"""Run an explicit matrix of benchmark conditions in separate Maven/JVM processes."""
import argparse
import json
import subprocess
import math
from pathlib import Path
from validate_episodes import read_json, require

PROPERTIES = {"bench.db.workers", "bench.db.serviceMs", "bench.db.thrash", "bench.app.workMs",
              "bench.poolSize", "bench.poolMin", "bench.poolMax", "bench.initialLimit", "bench.sloMs",
              "bench.loadScale", "bench.durationScale", "bench.degradedFactor"}


def commands(matrix, output, maven="mvn", repeats=3, modes="explore", scenarios="steady,spike,ramp,slow-dependency",
             seed=42, epsilon=0.5, pool_period=5):
    require(matrix.get("matrixVersion") == 1 and matrix.get("cases"), "invalid collection matrix")
    require(type(repeats) is int and repeats > 0, "repeats must be positive")
    require(math.isfinite(epsilon) and 0 <= epsilon <= 1, "epsilon must be in [0,1]")
    require(type(pool_period) is int and pool_period > 0, "pool period must be positive")
    output = Path(output).resolve()
    seen, result = set(), []
    for case in matrix["cases"]:
        name = case["id"]
        require(isinstance(name, str) and name and all(c in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in name),
                "invalid case id")
        require(name not in seen, "duplicate case id")
        seen.add(name)
        props = case["properties"]
        require(isinstance(props, dict) and props.keys() <= PROPERTIES, "unsupported matrix property")
        require(all(type(v) in (str, int, float) for v in props.values()), "matrix values must be scalar")
        args = [maven, "--batch-mode", "--no-transfer-progress", "-pl", "iasr-benchmark", "exec:java",
                f"-Dbench.out={output / name}", f"-Dbench.repeat={repeats}", f"-Dbench.modes={modes}",
                f"-Dbench.scenarios={scenarios}", f"-Dbench.policySeed={seed}", f"-Dbench.orderSeed={seed}"]
        args.extend((f"-Dbench.epsilon={epsilon}", f"-Dbench.poolPeriod={pool_period}"))
        args.extend(f"-D{k}={v}" for k, v in sorted(props.items()))
        result.append((name, args))
    return result


def collect(matrix, output, **options):
    output = Path(output)
    jobs = commands(matrix, output, **options)
    output.mkdir(parents=True, exist_ok=False)
    status = {"collectionVersion": 1, "status": "running", "matrix": matrix,
              "commands": [args for _, args in jobs], "completed": []}
    destination = output / "collection.json"
    destination.write_text(json.dumps(status, indent=2) + "\n")
    repo = Path(__file__).resolve().parents[3]
    for name, args in jobs:
        with (output / f"{name}.log").open("w") as log:
            subprocess.run(args, cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
        status["completed"].append(name)
        destination.write_text(json.dumps(status, indent=2) + "\n")
    status["status"] = "complete"
    destination.write_text(json.dumps(status, indent=2) + "\n")
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--modes", default="explore")
    parser.add_argument("--scenarios", default="steady,spike,ramp,slow-dependency")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--epsilon", type=float, default=0.5)
    parser.add_argument("--pool-period", type=int, default=5)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    options = dict(maven=args.maven, repeats=args.repeat, modes=args.modes, scenarios=args.scenarios, seed=args.seed,
                   epsilon=args.epsilon, pool_period=args.pool_period)
    try:
        matrix = read_json(args.matrix)
        result = [command for _, command in commands(matrix, args.out, **options)] if args.dry_run else collect(matrix, args.out, **options)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Collection failed: {error}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
