#!/usr/bin/env python3
"""Audit a frozen PostgreSQL comparison and compute within-repetition differences."""
import argparse
import hashlib
import json
from collections import Counter
from datetime import timedelta
from pathlib import Path
from statistics import mean, stdev

from audit_controller_comparison import classify_actions, live_records
from fqi_runtime import action_key, bin_for, supported_actions
from policy_data import LIMIT, POOL, candidates, context
from prepare_dataset import group_id
from replay_jvm_policy import replay
from validate_episodes import read_json, require, timestamp


def measured_windows(rows, start, end):
    for row in rows:
        left = row.get("stateWindowStartedAt")
        if left is None:
            continue
        left, right = timestamp(left), timestamp(row["timestamp"])
        observed = (right - left).total_seconds()
        if start <= left and right <= end and abs(observed - row["windowSec"]) <= max(0.1, 0.25 * row["windowSec"]):
            yield row, right


def recovery(rows, start, end, criterion, slo):
    disturbance_end = start + timedelta(seconds=criterion["disturbanceEndSec"])
    minimum_goodput = criterion["offeredRps"] * criterion["minGoodputFraction"]
    previous, streak, last_observed = None, 0, 0.0
    for row, right in measured_windows(rows, disturbance_end, end):
        last_observed = (right - disturbance_end).total_seconds()
        state = row["state"]
        latency, goodput = state.get("latency_p95_ms"), state.get("goodput_rps")
        if previous is None or row["sequence"] != previous + 1:
            streak = 0
        if latency is not None and 0 < latency <= slo and goodput is not None and goodput >= minimum_goodput:
            streak += 1
            if streak == criterion["consecutiveWindows"]:
                return {"seconds": last_observed, "censored": False}
        else:
            streak = 0
        previous = row["sequence"]
    return {"seconds": None, "censored": True, "observedUntilSeconds": last_observed}


def movement(rows):
    result = {}
    for actuator in (LIMIT, POOL):
        deltas = [row["action"][actuator] - row["resourcesBefore"][actuator] for row in rows]
        changes = [delta for delta in deltas if delta]
        values = [value for row in rows for value in (row["action"][actuator], row["resourcesBefore"][actuator])]
        result[actuator] = {"changes": len(changes), "totalVariation": sum(abs(delta) for delta in changes),
                            "directionReversals": sum(a * b < 0 for a, b in zip(changes, changes[1:])),
                            "minTarget": min(values, default=None), "maxTarget": max(values, default=None)}
    return result


def diagnose_support(rows, control, model):
    """Describe which support filter excludes all candidates, outside the inference timer."""
    support, preprocessing = model["support"], model["preprocessing"]
    counts = Counter()
    unsupported_bins = Counter()
    for record in live_records(rows, control):
        history = record["history"]
        if supported_actions(history, control, preprocessing, support):
            counts["hasSupportedAction"] += 1
            continue
        state_bin = bin_for(history, control)
        bucket = support["bins"].get(state_bin, {})
        entries = [bucket.get(action_key(action)) for action in candidates(history[-1], control)]
        has_coverage = any(entry is not None and entry["samples"] >= support["minSamples"]
                           and entry["episodes"] >= support["minEpisodes"] for entry in entries)
        counts["outsideRadius" if has_coverage else "noLegalActionWithBinCoverage"] += 1
        unsupported_bins[state_bin] += 1
    return {"counts": dict(counts), "unsupportedBins": dict(unsupported_bins)}


def paired_interval(deltas):
    require(len(deltas) >= 2, "paired interval requires at least two blocks")
    if any(delta is None for delta in deltas):
        return {"deltas": deltas, "mean": None, "ci95": None}
    from scipy.stats import t
    average = mean(deltas)
    half_width = t.ppf(0.975, len(deltas) - 1) * stdev(deltas) / len(deltas) ** 0.5
    return {"deltas": deltas, "mean": average, "ci95": [average - half_width, average + half_width]}


def metric_value(result, metric):
    if metric in ("p95Ms", "p99Ms", "sloBreachPercent") and result["succeeded"] == 0:
        return None
    return result[metric]


def assert_conditions(metadata, plan):
    config, properties = metadata["configuration"], plan["properties"]
    conditions = config["conditions"]
    backend, profile = conditions["backend"], conditions["profile"]
    require(backend["kind"] == "postgres" and properties["bench.db.real"] is True, "unexpected dependency")
    expected = {"serviceMs": properties["bench.db.serviceMs"], "rowsPerMs": properties["bench.db.rowsPerMs"]}
    require(all(backend[key] == value for key, value in expected.items()), "query cost differs from protocol")
    require(profile["degradedFactor"] == properties["bench.degradedFactor"]
            and profile["loadScale"] == properties["bench.loadScale"]
            and profile["durationScale"] == properties.get("bench.durationScale", 1.0), "workload differs from protocol")
    require(conditions["initialPool"] == properties["bench.poolSize"]
            and conditions["initialLimit"] == properties["bench.initialLimit"]
            and conditions["sloMs"] == properties["bench.sloMs"], "initial conditions differ from protocol")
    require(metadata["initialResources"] == {POOL: properties["bench.poolSize"], LIMIT: properties["bench.initialLimit"]},
            "actual initial resources differ from protocol")
    require(config["iasr"]["applyActions"] == (metadata["mode"] != "fixed"), "unexpected control mode")
    rules = {rule["actuatorName"]: rule for rule in config["guardrails"]}
    mode = metadata["mode"]
    bounds = (properties["bench.poolMin"], properties["bench.poolMax"])
    if mode.endswith("-pinned"):
        bounds = (properties["bench.poolSize"],) * 2
    require((rules[POOL]["minValue"], rules[POOL]["maxValue"]) == bounds
            and (config["iasr"]["hikari"]["minPoolSize"], config["iasr"]["hikari"]["maxPoolSize"]) == bounds,
            "pool bounds differ from protocol")
    if mode.startswith("learned"):
        learned = config["learned"]
        sha_key = "additiveModelSha256" if mode == "learned-additive" else "modelSha256"
        require(learned["modelSha256"] == plan[sha_key]
                and learned["poolPeriodTicks"] == properties["bench.poolPeriod"]
                and learned["inferenceBudgetMs"] == properties["bench.inferenceBudgetMs"], "policy differs from protocol")


def differences_for(cases, repeats, metrics, mode, reference):
    differences = {}
    for metric in metrics:
        deltas = []
        for rep in repeats:
            value = metric_value(cases[rep, mode]["result"], metric)
            baseline = metric_value(cases[rep, reference]["result"], metric)
            deltas.append(round(value - baseline, 6) if value is not None and baseline is not None else None)
        differences[metric] = paired_interval(deltas)
    return differences


def audit(root, protocol_path, model_path, additive_path=None):
    root, protocol_path, model_path = Path(root), Path(protocol_path), Path(model_path)
    plan, execution = read_json(protocol_path), read_json(root / "execution.json")
    protocol_sha = hashlib.sha256(protocol_path.read_bytes()).hexdigest()
    require(plan["protocolVersion"] == 1 and len(plan["scenarios"]) == 1, "expected a single-scenario protocol")
    require(execution["status"] == "complete" and execution["postgresStopped"], "benchmark or database shutdown incomplete")
    require(execution["protocolSha256"] == protocol_sha and execution["postgresVersion"] == plan["postgresVersion"],
            "execution does not match the protocol")
    calibration = protocol_path.parent / plan["calibration"]["file"]
    require(hashlib.sha256(calibration.read_bytes()).hexdigest() == plan["calibration"]["sha256"], "calibration changed")
    require(hashlib.sha256(model_path.read_bytes()).hexdigest() == plan["modelSha256"], "model changed")
    model = read_json(model_path)
    models = {mode: model for mode in ("learned", "learned-pinned")}
    audit_replay = replay(root, model_path)
    replayed = {episode["episodeId"]: episode for episode in audit_replay["replay"]}
    scenario, = plan["scenarios"]
    modes = plan["modes"]
    if "learned-additive" in modes:
        require(additive_path is not None, "additive model file is required")
        additive_path = Path(additive_path)
        require(hashlib.sha256(additive_path.read_bytes()).hexdigest() == plan["additiveModelSha256"], "additive model changed")
        additive = read_json(additive_path)
        require(model["trainingConfig"]["regressor"] == "per_action"
                and additive["trainingConfig"]["regressor"] == "additive", "wrong ablation model types")
        for name in ("inputSha256", "inputGroups", "support", "preprocessing", "rewardWeights"):
            require(model[name] == additive[name], f"ablation inputs differ: {name}")
        require(model["actionForests"].keys() == additive["actionForests"].keys()
                and all(len(model["actionForests"][key]) == len(additive["actionForests"][key])
                        for key in model["actionForests"]), "ablation scoring budgets differ")
        models["learned-additive"] = additive
        other = replay(root, additive_path, ("learned-additive",))
        replayed.update({episode["episodeId"]: episode for episode in other["replay"]})
    repeats = range(1, plan["repeats"] + 1)
    require(len(modes) > 1 and len(set(modes)) == len(modes) and len(repeats) >= 2
            and len(repeats) % len(modes) == 0, "expected full balanced blocks")
    cases, conditions, sources, run_ids, controller_settings = {}, set(), set(), set(), set()
    for path in sorted(root.rglob("metadata.json")):
        metadata = read_json(path)
        mode, repetition = metadata["mode"], metadata["repetition"]
        key = (repetition, mode)
        require(metadata["scenario"] == scenario and repetition in repeats and mode in modes
                and key not in cases, "unexpected or duplicate episode")
        require(metadata["source"]["dirty"] is False and metadata["orderSeed"] == plan["orderSeed"],
                "source or order seed differs")
        assert_conditions(metadata, plan)
        config = metadata["configuration"]
        settings = {key: value for key, value in config["iasr"].items() if key != "applyActions"}
        settings["hikari"] = {key: value for key, value in settings["hikari"].items()
                              if key not in ("minPoolSize", "maxPoolSize")}
        rules = [{key: value for key, value in rule.items()
                  if rule["actuatorName"] != POOL or key not in ("minValue", "maxValue")} for rule in config["guardrails"]]
        controller_settings.add(json.dumps([settings, rules], sort_keys=True))
        conditions.add(group_id(metadata))
        sources.add(metadata["source"]["commit"])
        run_ids.add(metadata["runId"])
        result = read_json(path.with_name("result.json"))
        require(result["scenario"] == scenario and result["mode"] == mode, "result identity differs")
        timing = metadata["timing"]
        start = timestamp(timing["measurementStartedAt"])
        end = timestamp(timing["measurementScheduledEndAt"])
        require(result["durationMs"] == (end - start).total_seconds() * 1000, "measurement duration differs")
        rows = [json.loads(line) for line in path.with_name("dataset.jsonl").read_text().splitlines()]
        measured = [row for row, _ in measured_windows(rows, start, end)]
        case = {"repetition": repetition, "mode": mode, "episodePath": str(path.parent.relative_to(root)),
                "startedAt": timing["captureStartedAt"], "result": result, "measuredWindows": len(measured),
                "measurementInitialResources": metadata["measurementInitialResources"],
                "measurementMovement": movement(measured),
                "recovery": recovery(rows, start, end, plan["analysis"]["recovery"], plan["properties"]["bench.sloMs"])}
        if mode == "fixed":
            require(all(item["changes"] == 0 for item in movement(rows).values()), "fixed controller moved resources")
        if mode.endswith("-pinned"):
            require(movement(rows)[POOL]["changes"] == 0, "pinned pool moved")
        if mode in models:
            actions = classify_actions(rows)
            counts = replayed[metadata["episodeId"]]["counts"]
            require(actions["modelDecisions"] == metadata["inference"]["modelDecisions"]
                    == counts.get("matched_model_decisions", 0), "some learned decisions cannot be replayed")
            require(actions["fallbackReasons"].get("learned_fallback_no_empirical_support", 0)
                    == counts.get("matched_support_fallbacks", 0), "some support fallbacks cannot be replayed")
            case.update(inference=metadata["inference"], learnedActions=actions,
                        measuredLearnedActions=classify_actions(measured), replayCounts=counts,
                        supportDiagnostics=diagnose_support(rows, context(metadata), models[mode]))
        cases[key] = case
    require(set(cases) == {(rep, mode) for rep in repeats for mode in modes}, "paired episodes missing")
    require(len(conditions) == len(sources) == len(run_ids) == len(controller_settings) == 1,
            "condition, controller settings, source or run differs between modes")
    condition, = conditions
    require(condition not in model["inputGroups"], "evaluation condition occurs in model inputs")
    require(condition != plan.get("reservedEvaluationGroup"), "evaluation reused the prior holdout group")
    orders = [[case["mode"] for case in sorted((cases[rep, mode] for mode in modes),
              key=lambda item: timestamp(item["startedAt"]))] for rep in repeats]
    for index, order in enumerate(orders):
        rotation = index % len(modes)
        require(order == orders[0][rotation:] + orders[0][:rotation], "execution order differs from balanced rotation")
    ordered_cases = [cases[rep, mode] for rep, order in zip(repeats, orders) for mode in order]
    require(ordered_cases == sorted(ordered_cases, key=lambda item: timestamp(item["startedAt"])), "blocks are out of order")
    reference = plan["analysis"]["reference"]
    metrics = plan["analysis"]["primary"] + plan["analysis"]["secondary"]
    differences = {mode: differences_for(cases, repeats, metrics, mode, reference) for mode in modes if mode != reference}
    report = {"analysisVersion": 1, "protocolSha256": protocol_sha, "modelSha256": plan["modelSha256"],
            "conditionGroup": condition, "sourceCommit": next(iter(sources)), "scenario": scenario,
            "integrity": audit_replay["integrity"], "observedModeOrder": orders,
            "cases": ordered_cases,
            "reference": reference, "differenceDirection": "mode_minus_reference", "pairedDifferences": differences,
            "intervalMethod": plan["analysis"]["interval"], "heldOutConditions": True}
    if additive_path is not None:
        report["additiveModelSha256"] = plan["additiveModelSha256"]
    if "contrasts" in plan["analysis"]:
        report["plannedContrasts"] = [
            {"mode": mode, "reference": baseline, "metrics": differences_for(cases, repeats, metrics, mode, baseline)}
            for mode, baseline in plan["analysis"]["contrasts"]]
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("model", type=Path)
    parser.add_argument("--additive-model", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.raw, args.protocol, args.model, args.additive_model)
        with args.out.open("x", encoding="utf-8") as output:
            json.dump(result, output, indent=2, sort_keys=True, allow_nan=False)
            output.write("\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"PostgreSQL comparison audit failed: {error}\n")
    print(f"Audited {len(result['cases'])} episodes in {result['scenario']}")
