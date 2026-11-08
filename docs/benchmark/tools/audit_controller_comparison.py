#!/usr/bin/env python3
"""Audit model-only actions, fallbacks and case parity in a predeclared technical comparison."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from replay_jvm_policy import replay
from validate_episodes import require, read_json, validate_collection
from policy_data import LIMIT, POOL, observation, context
from audit_joint_learning import sensitivity, action_node_coverage
import math


def classify_actions(rows):
    model_actions, fallback_actions, reasons = Counter(), Counter(), Counter()
    for row in rows:
        modeled = row["reasons"][LIMIT].startswith("learned_policy")
        require(row["reasons"][POOL].startswith("learned_policy") == modeled, "inconsistent controller provenance")
        delta = tuple(row["action"][name] - row["resourcesBefore"][name] for name in (LIMIT, POOL))
        if modeled:
            model_actions[delta] += 1
        else:
            require(row["reasons"][LIMIT].startswith("learned_fallback_"), "unknown decision provenance")
            fallback_actions[delta] += 1
            reasons[row["reasons"][LIMIT].split(":")[0].split("|")[0]] += 1
    def encode(counter):
        return {f"{dl},{dp}": n for (dl, dp), n in sorted(counter.items())}
    return {"modelActions": encode(model_actions), "fallbackActions": encode(fallback_actions),
            "fallbackReasons": dict(sorted(reasons.items())), "modelDecisions": sum(model_actions.values()),
            "modelLimitChanges": sum(n for (dl, dp), n in model_actions.items() if dl != 0),
            "modelPoolChanges": sum(n for (dl, dp), n in model_actions.items() if dp != 0),
            "modelSimultaneousChanges": sum(n for (dl, dp), n in model_actions.items() if dl != 0 and dp != 0)}


def live_records(rows, control):
    records = []
    for end in range(3, len(rows)):
        block = rows[end - 3:end + 1]
        ticks = [r["state"].get("control_tick") for r in block]
        intervals = [r["state"].get("observation_elapsed_sec") for r in block]
        if not all(type(t) in (int, float) and math.isfinite(t) and t >= 0 and t == int(t) for t in ticks):
            continue
        if any(b != a + 1 for a, b in zip(ticks, ticks[1:])):
            continue
        if not all(type(t) in (int, float) and math.isfinite(t) and t > 0
                   and abs(t - control["windowSec"]) <= max(0.1, 0.25 * control["windowSec"]) for t in intervals):
            continue
        if not all(r["state"].get("control_window_sec") == control["windowSec"] for r in block):
            continue
        records.append({"history": [observation(r) for r in block], "context": control})
    return records


def audit(root, models, plan_path):
    root, models, plan_path = Path(root), Path(models), Path(plan_path)
    execution, plan = read_json(root / "execution.json"), read_json(plan_path)
    require(execution["status"] == "complete" and execution["plan"] == plan, "executed plan differs or comparison incomplete")
    require([[c["scenario"], c["arm"]] for c in execution["completed"]] == plan["order"], "case order differs from registered plan")
    if plan.get("selectionPolicy") == "predeclared_method_comparison":
        require(set(plan["arms"]) == {"experimental", "reference"}, "invalid method comparison roles")
        protocol = read_json(models / "protocol.json")
        require(protocol["selectionPolicy"] == plan["selectionPolicy"]
                and protocol["arms"] == {arm: spec["candidate"] for arm, spec in plan["arms"].items()}, "method roles differ from protocol")
        selection = {"selected": None}
    else:
        selection = read_json(models / "selection.json")
        require(plan["selectionUnchanged"] == selection["selected"] == plan["arms"]["selected"]["candidate"], "metric winner was replaced")
    integrity, replays, cases, conditions, sources = validate_collection(root), {}, {}, {}, set()
    for arm, spec in plan["arms"].items():
        model_path = models / spec["candidate"] / "model.json"
        require(hashlib.sha256(model_path.read_bytes()).hexdigest() == spec["modelSha256"], "model differs from registered plan")
        replays[arm] = replay(root / arm, model_path)
        replayed = {r["episodeId"]: r for r in replays[arm]["replay"]}
        for metadata_path in sorted((root / arm).rglob("metadata.json")):
            metadata = read_json(metadata_path)
            scenario = metadata["scenario"]
            require([scenario, arm] in plan["order"] and (scenario, arm) not in cases, "unexpected or duplicate case")
            require(metadata["source"]["dirty"] is False, "uncommitted application source")
            sources.add(metadata["source"]["commit"])
            config = metadata["configuration"]
            comparable = {key: config[key] for key in ("conditions", "guardrails", "iasr", "controllerClass")}
            require(scenario not in conditions or conditions[scenario] == comparable, "object/controller conditions differ across arms")
            conditions[scenario] = comparable
            rows = [json.loads(line) for line in metadata_path.with_name("dataset.jsonl").read_text().splitlines()]
            actions = classify_actions(rows)
            count = actions["modelDecisions"]
            observed_replay = replayed[metadata["episodeId"]]
            require(observed_replay["counts"].get("matched_model_decisions", 0) == count, "not all model decisions replayed")
            require(metadata["inference"]["modelDecisions"] == count, "model decision records missing")
            records = live_records(rows, context(metadata))
            model = read_json(model_path)
            cases[(scenario, arm)] = {"scenario": scenario, "arm": arm, "candidate": spec["candidate"],
                "episodePath": str(metadata_path.parent.relative_to(root)), "records": metadata["datasetRecords"],
                **actions, "inference": metadata["inference"], "result": read_json(metadata_path.with_name("result.json")),
                "modelOnLiveObservations": sensitivity(records, model),
                "actionNodesOnLiveObservations": action_node_coverage({"live": records}, model)}
    require(len(sources) == 1 and len(cases) == len(plan["order"]), "source or case set differs")
    return {"auditVersion": 1, "purpose": plan["purpose"], "selectionUnchanged": selection["selected"],
            "planSha256": hashlib.sha256(plan_path.read_bytes()).hexdigest(), "sourceCommit": next(iter(sources)),
            "integrity": integrity, "cases": [cases[tuple(key)] for key in plan["order"]], "replay": replays,
            "testEvaluated": False, "performanceEstablished": False,
            "limitation": "one repeat per arm/profile, separate JVMs, known development conditions; no inferential performance comparison"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("models", type=Path)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.raw, args.models, args.plan)
        with args.out.open("x") as out:
            out.write(json.dumps(result, sort_keys=True, indent=2) + "\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Technical comparison audit failed: {error}\n")
    print(json.dumps({"cases": len(result["cases"]), "selectionUnchanged": result["selectionUnchanged"],
                      "modelPoolChanges": {f"{c['scenario']}/{c['arm']}": c["modelPoolChanges"] for c in result["cases"]}}))
