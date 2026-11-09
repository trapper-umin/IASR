#!/usr/bin/env python3
"""Run or audit a blocked, matched CPU-pressure experiment on PostgreSQL."""
import argparse
import hashlib
import json
import subprocess
from collections import Counter
from pathlib import Path

from audit_controller_comparison import classify_actions
from audit_postgres_transfer import assert_conditions, measured_windows, metric_value, movement, paired_interval
from benchmark_cpu import read_cpu_trace, summarize_cpu
from prepare_dataset import group_id
from replay_jvm_policy import replay
from run_postgres_transfer import run
from validate_episodes import read_json, require, timestamp, validate_episode


def schedule(plan):
    require(plan["purpose"] == "held_out_postgresql_cpu_pressure", "expected a CPU pressure protocol")
    arms = {(mode, workers) for mode in ("adaptive", "learned") for workers in (0, plan["pressureWorkers"])}
    blocks = [[tuple(arm) for arm in block] for block in plan["blocks"]]
    require(len(arms) == len(blocks) == 4 and all(len(block) == 4 and set(block) == arms for block in blocks),
            "expected four complete factorial blocks")
    positions = Counter((position, arm) for block in blocks for position, arm in enumerate(block))
    neighbors = Counter(pair for block in blocks for pair in zip(block, block[1:]))
    require(len(positions) == 16 and len(neighbors) == 12, "trial order must balance position and first-order carryover")
    for block, arms_in_order in enumerate(blocks, 1):
        for position, (mode, workers) in enumerate(arms_in_order, 1):
            yield {"block": block, "position": position, "mode": mode, "cpuWorkers": workers}


def trial_name(trial):
    return f"block-{trial['block']:02d}/trial-{trial['position']:02d}"


def execute(protocol, output):
    trials = list(schedule(read_json(protocol)))
    output.mkdir(parents=True)
    for trial in trials:
        print(f"Block {trial['block']}, position {trial['position']}: {trial['mode']} with {trial['cpuWorkers']} workers", flush=True)
        run(protocol, output / trial_name(trial), trial=(trial["block"], trial["position"]))


def inspect_trial(directory, trial, plan, protocol_sha, model_path):
    execution = read_json(directory / "execution.json")
    interval = plan["cpuSamplingSec"]
    require(execution["trial"] == trial and execution["protocolSha256"] == protocol_sha
            and execution["postgresVersion"] == plan["postgresVersion"] and execution["cpuSamplingSec"] == interval,
            "trial execution differs from frozen protocol")
    require(execution["status"] == "complete" and execution["postgresStopped"] and execution["cpuPressureStopped"],
            "trial or process cleanup incomplete")
    episodes = list(directory.rglob("metadata.json"))
    require(len(episodes) == 1, "expected one episode per trial")
    path, = episodes
    integrity = validate_episode(path.parent)
    meta = read_json(path)
    mode, workers = trial["mode"], trial["cpuWorkers"]
    require(meta["mode"] == mode and meta["scenario"] == plan["scenarios"][0] and meta["repetition"] == 1
            and meta["source"]["dirty"] is False and meta["orderSeed"] == plan["orderSeed"], "episode identity differs")
    assert_conditions(meta, plan)
    result = read_json(path.with_name("result.json"))
    timing = meta["timing"]
    start, end = map(timestamp, (timing["measurementStartedAt"], timing["measurementScheduledEndAt"]))
    require(result["mode"] == mode and result["scenario"] == meta["scenario"]
            and result["durationMs"] == (end - start).total_seconds() * 1000, "result identity differs")
    samples = read_cpu_trace(directory / "cpu.jsonl")
    cpu = summarize_cpu(samples, start, end, interval)
    require(cpu["fractionCovered"] >= plan["analysis"]["minCpuCoverage"], "insufficient CPU sampling")
    require(("pressure" in cpu) == bool(workers), "pressure trace differs from assigned treatment")
    exposure = (cpu["pressure"]["meanCores"] >= plan["analysis"]["minPressureCores"]
                and cpu["pressure"]["started"] == cpu["pressure"]["exited"] == 0) if workers else True
    rows = [json.loads(line) for line in path.with_name("dataset.jsonl").read_text().splitlines()]
    measured = [row for row, _ in measured_windows(rows, start, end)]
    case = {**trial, "episodePath": str(path.parent.relative_to(directory)),
            "startedAt": execution["startedAt"], "endedAt": execution["endedAt"], "source": meta["source"]["commit"],
            "runId": meta["runId"], "conditionGroup": group_id(meta), "integrity": integrity, "result": result,
            "measurementCpu": cpu, "pressureExposureMet": exposure, "measurementInitialResources": meta["measurementInitialResources"],
            "measuredWindows": len(measured), "measurementMovement": movement(measured)}
    if mode == "learned":
        counts = replay(directory, model_path)["replay"][0]["counts"]
        actions = classify_actions(rows)
        require(counts.get("matched_model_decisions", 0) == actions["modelDecisions"] == meta["inference"]["modelDecisions"]
                and counts.get("matched_support_fallbacks", 0) == actions["fallbackReasons"].get("learned_fallback_no_empirical_support", 0),
                "some learned decisions cannot be replayed")
        case.update(replayCounts=counts, measuredLearnedActions=classify_actions(measured), inference=meta["inference"])
    config = meta["configuration"]
    effective = json.dumps([config["iasr"], config["guardrails"], execution["postgresSettings"]], sort_keys=True)
    return case, effective


def audit(protocol, root):
    plan = read_json(protocol)
    trials = list(schedule(plan))
    protocol_sha = hashlib.sha256(protocol.read_bytes()).hexdigest()
    repo = Path(__file__).resolve().parents[3]
    model_path = repo / plan["model"]
    require(hashlib.sha256(model_path.read_bytes()).hexdigest() == plan["modelSha256"], "model changed")
    calibration = protocol.parent / plan["calibration"]["file"]
    require(hashlib.sha256(calibration.read_bytes()).hexdigest() == plan["calibration"]["sha256"], "calibration changed")
    require({str(path.parent.relative_to(root)) for path in root.rglob("execution.json")} == {trial_name(t) for t in trials},
            "unexpected or missing trials")
    inspected = [inspect_trial(root / trial_name(t), t, plan, protocol_sha, model_path) for t in trials]
    cases = [case for case, _ in inspected]
    require(len({settings for _, settings in inspected}) == 1, "object or controller settings changed")
    require(all(timestamp(a["endedAt"]) <= timestamp(b["startedAt"]) for a, b in zip(cases, cases[1:])), "trials overlap or are out of order")
    require(len({case["runId"] for case in cases}) == 16 and len({case["source"] for case in cases}) == 1, "source or trial isolation differs")
    groups = {case["conditionGroup"] for case in cases}
    model = read_json(model_path)
    require(len(groups) == 1 and groups.isdisjoint(model["inputGroups"])
            and groups.isdisjoint(plan["reservedEvaluationGroups"]), "condition is not a single fresh holdout")
    high = plan["pressureWorkers"]
    results = {(case["block"], case["mode"], case["cpuWorkers"]): case["result"] for case in cases}
    contrasts = {
        "pressureOnAdaptive": [("adaptive", high, 1), ("adaptive", 0, -1)],
        "pressureOnLearned": [("learned", high, 1), ("learned", 0, -1)],
        "learnedMinusAdaptiveControl": [("learned", 0, 1), ("adaptive", 0, -1)],
        "learnedMinusAdaptivePressure": [("learned", high, 1), ("adaptive", high, -1)],
        "differenceInPressureEffect": [("learned", high, 1), ("learned", 0, -1),
                                       ("adaptive", high, -1), ("adaptive", 0, 1)],
    }
    paired = {}
    for name, terms in contrasts.items():
        paired[name] = {}
        for metric in plan["analysis"]["primary"] + plan["analysis"]["secondary"]:
            deltas = []
            for block in range(1, 5):
                values = [(metric_value(results[block, mode, workers], metric), sign) for mode, workers, sign in terms]
                deltas.append(round(sum(value * sign for value, sign in values), 6)
                              if all(value is not None for value, _ in values) else None)
            paired[name][metric] = paired_interval(deltas)
    return {"analysisVersion": 1, "protocolSha256": protocol_sha, "modelSha256": plan["modelSha256"],
            "conditionGroup": next(iter(groups)), "cases": cases, "pairedContrasts": paired,
            "pressureExposureMet": all(case["pressureExposureMet"] for case in cases),
            "intervalMethod": "paired_t_95_unadjusted_exploratory"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--audit-out", type=Path, help="audit an existing study and write a new JSON report")
    args = parser.parse_args()
    try:
        if args.audit_out is None:
            execute(args.protocol, args.directory)
        else:
            report = audit(args.protocol, args.directory)
            with args.audit_out.open("x", encoding="utf-8") as output:
                json.dump(report, output, indent=2, sort_keys=True, allow_nan=False)
                output.write("\n")
            print(f"Audited {len(report['cases'])} matched trials")
    except subprocess.CalledProcessError as error:
        parser.exit(1, f"Trial subprocess exited with status {error.returncode}. See its logs.\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"CPU pressure study failed: {error}\n")
