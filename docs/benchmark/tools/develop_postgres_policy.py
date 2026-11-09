#!/usr/bin/env python3
"""Extend frozen synthetic train with a PostgreSQL development-only collection."""
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from audit_joint_learning import audit
from audit_postgres_transfer import assert_conditions
from extend_development_dataset import digest, extend
from fqi_runtime import load_model, supported_actions
from policy_data import state_bin
from prepare_dataset import group_id, prepare
from train_fqi import cache_records, joint_readiness, load_partitions, source_version, train
from validate_episodes import read_json, require


def check_collection(raw, protocol, plan):
    execution = read_json(raw / "execution.json")
    require(execution["status"] == "complete" and execution["postgresStopped"], "collection or database shutdown incomplete")
    require(execution["protocolSha256"] == digest(protocol) and execution["postgresVersion"] == plan["postgresVersion"],
            "collection differs from frozen protocol")
    props, development = plan["properties"], plan["development"]
    expected = {(scenario, repeat) for scenario in plan["scenarios"] for repeat in range(1, plan["repeats"] + 1)}
    seen, sources, runs = set(), set(), set()
    groups = defaultdict(set)
    # Check reservations and experiment identity before opening any dataset file.
    for path in sorted(raw.rglob("metadata.json")):
        meta = read_json(path)
        condition = group_id(meta)
        require(condition != development["reservedEvaluationGroup"], "reserved evaluation group in development input")
        key = (meta["scenario"], meta["repetition"])
        require(key in expected and key not in seen and meta["mode"] == "explore-balanced",
                "unexpected or duplicate development episode")
        assert_conditions(meta, plan)
        exploration = meta["configuration"]["exploration"]
        require(exploration["sampling"] == "balanced" and exploration["epsilon"] == props["bench.epsilon"]
                and exploration["poolPeriodTicks"] == props["bench.poolPeriod"], "exploration differs from protocol")
        require(meta["source"]["dirty"] is False and meta["orderSeed"] == plan["orderSeed"], "source or order seed differs")
        seen.add(key)
        groups[meta["scenario"]].add(condition)
        sources.add(meta["source"]["commit"])
        runs.add(meta["runId"])
    require(seen == expected and len(sources) == len(runs) == 1, "expected one complete development run")
    require(all(len(conditions) == 1 for conditions in groups.values()), "conditions changed between repetitions")


def validation_support(rows, model):
    total, bins, episodes = Counter(), defaultdict(Counter), defaultdict(Counter)
    for row in rows:
        legal = row["candidateActions"]
        supported = supported_actions(row["history"], row["context"], model["preprocessing"], model["support"], legal)
        counts = {"histories": 1, "anySupportedAction": bool(supported), "observedActionSupported": row["action"] in supported,
                  "poolActionLegal": any(dp for _, dp in legal), "poolActionSupported": any(dp for _, dp in supported),
                  "jointActionLegal": any(dl and dp for dl, dp in legal),
                  "jointActionSupported": any(dl and dp for dl, dp in supported)}
        for counter in (total, bins[state_bin(row)], episodes[row["episodeId"]]):
            counter.update(counts)
    return {"total": dict(total), "byBin": dict(bins), "byEpisode": dict(episodes)}


def write_json(path, content):
    with path.open("x", encoding="utf-8") as destination:
        json.dump(content, destination, sort_keys=True, indent=2, allow_nan=False)
        destination.write("\n")


def develop(raw, protocol, prior, output):
    plan = read_json(protocol)
    require(plan["protocolVersion"] == 1 and plan["purpose"] == "postgresql_development_only"
            and plan["modes"] == ["explore-balanced"], "expected a PostgreSQL development protocol")
    check_collection(raw, protocol, plan)
    settings = plan["development"]
    repo = Path(__file__).resolve().parents[3]
    require(not output.resolve().is_relative_to(repo), "use an output directory outside the repository")
    require(not source_version()["dirty"], "working tree must be clean")
    reference_path = repo / plan["model"]
    require(digest(reference_path) == plan["modelSha256"], "reference model changed")
    reference = load_model(reference_path)
    require(settings["retainOriginalSyntheticTrain"] is True and
            digest(prior / "train.jsonl") == reference["inputSha256"]["train.jsonl"]
            and digest(prior / "manifest.json") == reference["inputSha256"]["manifest.json"],
            "prior train or its provenance differs from the reference model")
    reserved = settings["reservedEvaluationGroup"]
    require(reserved not in reference["inputGroups"], "reference used the reserved evaluation group")
    output.mkdir(parents=True)
    current, combined = output / "postgres", output / "combined"
    prepare(raw, current, validation_scenarios=tuple(settings["validationScenarios"]), development_only=True)
    manifest = extend(prior, current, combined)
    require(reserved not in manifest["groups"], "reserved evaluation group in prepared data")
    support_options = {"min_samples": settings["minSamples"], "min_episodes": settings["minEpisodes"],
                       "max_distance": settings["maxDistance"]}
    joint = audit(combined, **support_options)
    write_json(output / "joint-audit.json", joint)
    _, parts = load_partitions(combined, splits=("train", "validation"))
    validation = parts["validation"]
    new_groups = read_json(current / "manifest.json")["groups"]
    postgres_train = [row for row in parts["train"] if row["groupId"] in new_groups]
    report = {"analysisVersion": 1, "protocolSha256": digest(protocol), "reservedEvaluationGroup": reserved,
              "validationSha256": manifest["validationSha256"], "testEvaluated": False,
              "postgresTrain": {"input": len(postgres_train), "beforeClosure": joint_readiness(postgres_train)},
              "reference": {"modelSha256": digest(reference_path), "support": validation_support(validation, reference)}}
    if joint["necessaryJointCoverageEstablished"]:
        model_output = output / "model"
        model, metrics = train(combined, model_output, development_only=True, regressor=settings["regressor"],
                               iterations=settings["iterations"], trees=settings["trees"], depth=settings["depth"],
                               leaf=settings["leaf"], seed=settings["seed"], max_features=settings["maxFeatures"],
                               gamma=settings["gamma"], **support_options)
        retained, _ = cache_records(parts["train"], model["preprocessing"], model["support"])
        require(len(retained) == metrics["counts"]["train"]["scored"], "retained train differs from training report")
        postgres_retained = [item["record"] for item in retained if item["record"]["groupId"] in new_groups]
        report["postgresTrain"].update(retained=len(postgres_retained), afterClosure=joint_readiness(postgres_retained))
        report["candidate"] = {"status": "trained", "modelSha256": digest(model_output / "model.json"),
                               "support": validation_support(validation, model)}
    else:
        report["candidate"] = {"status": "insufficient_joint_support"}
    write_json(output / "support-comparison.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("prior", type=Path, help="reconstructed inputs of the frozen synthetic reference")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = develop(args.raw, args.protocol, args.prior, args.out)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"PostgreSQL development failed: {error}\n")
    print(result["candidate"]["status"])
