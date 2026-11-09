#!/usr/bin/env python3
"""Measure retained joint coverage from a train-only PostgreSQL pilot."""
import argparse
import json
from pathlib import Path

from develop_postgres_policy import check_collection, validation_support, write_json
from extend_development_dataset import digest, extend
from fqi_features import fit_preprocessor
from fqi_runtime import load_model
from prepare_dataset import coverage, prepare
from train_fqi import close_training_support, joint_readiness, load_partitions, source_version, train
from validate_episodes import read_json, require


def joint_coverage(rows, minimum_samples, minimum_episodes):
    audit = coverage(rows, min_samples=minimum_samples, min_episodes=minimum_episodes)
    buckets = {}
    for label, actions in audit["bins"].items():
        joint = {action: stats for action, stats in actions.items() if all(json.loads(action))}
        if joint:
            buckets[label] = joint
    return {"histories": len(rows), "historiesWithJointOption": sum(
                any(dl and dp for dl, dp in row["candidateActions"]) for row in rows),
            "observed": joint_readiness(rows), "jointBuckets": buckets,
            "qualifies": any(stats["supported"] for actions in buckets.values() for stats in actions.values())}


def fit(raw, protocol, prior, output):
    plan = read_json(protocol)
    require(plan["protocolVersion"] == 1 and plan["purpose"] == "postgresql_joint_action_development_pilot"
            and plan["modes"] == ["explore-joint"], "expected a joint-action pilot protocol")
    check_collection(raw, protocol, plan)
    repo = Path(__file__).resolve().parents[3]
    require(not output.resolve().is_relative_to(repo), "use an output directory outside the repository")
    require(not source_version()["dirty"], "working tree must be clean")
    reference_path = repo / plan["model"]
    require(digest(reference_path) == plan["modelSha256"], "reference model changed")
    reference = load_model(reference_path)
    for name in ("manifest.json", "train.jsonl", "validation.jsonl"):
        require(digest(prior / name) == reference["inputSha256"][name], f"frozen prior {name} differs from reference input")
    reserved = plan["development"]["reservedEvaluationGroup"]
    require(reserved not in reference["inputGroups"], "reference used the reserved evaluation group")

    output.mkdir(parents=True)
    pilot, combined = output / "pilot", output / "combined"
    added = prepare(raw, pilot, train_only=True)
    manifest = extend(pilot, prior, combined)
    require(reserved not in manifest["groups"], "reserved evaluation group in combined input")
    require(manifest["validationSha256"] == reference["inputSha256"]["validation.jsonl"], "validation changed")
    _, parts = load_partitions(combined, splits=("train", "validation"))
    is_pilot = {(episode["runId"], episode["episodeId"]) for episode in added["episodes"]}
    new_rows = [row for row in parts["train"] if (row["runId"], row["episodeId"]) in is_pilot]

    support = reference["support"]
    support_options = {"min_samples": support["minSamples"], "min_episodes": support["minEpisodes"],
                       "max_distance": support["maxRmsDistance"]}
    train_rows = parts["train"]
    closure_trace = []
    _, cached, excluded = close_training_support(train_rows, fit_preprocessor(train_rows), **support_options,
                                                trace=closure_trace)
    retained = [item["record"] for item in cached]
    retained_pilot = [row for row in retained if (row["runId"], row["episodeId"]) in is_pilot]
    thresholds = plan["development"]
    minimum = (thresholds["minJointSamples"], thresholds["minJointEpisodes"])
    before, after = joint_coverage(new_rows, *minimum), joint_coverage(retained_pilot, *minimum)
    mixed_joint = joint_readiness(retained)
    report = {"analysisVersion": 1, "protocolSha256": digest(protocol), "testEvaluated": False,
              "reservedEvaluationGroup": reserved, "validationSha256": manifest["validationSha256"],
              "reference": {"modelSha256": digest(reference_path),
                            "validationSupport": validation_support(parts["validation"], reference)},
              "pilotBeforeClosure": before, "pilotAfterClosure": after, "mixedTrainAfterClosure": mixed_joint,
              "closureTrace": closure_trace, "trainExcluded": excluded}
    if after["qualifies"] and mixed_joint["established"]:
        config = reference["trainingConfig"]
        model_path = output / "model"
        model, _ = train(combined, model_path, development_only=True, regressor=config["regressor"],
                         iterations=config["iterations"], trees=config["trees"], depth=config["depth"], leaf=config["minLeaf"],
                         seed=config["seed"], max_features=config["maxFeatures"], gamma=config["gammaPerSecond"], **support_options)
        report["candidate"] = {"status": "trained", "modelSha256": digest(model_path / "model.json"),
                               "validationSupport": validation_support(parts["validation"], model)}
    else:
        report["candidate"] = {"status": "insufficient_joint_support"}
    write_json(output / "analysis.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("protocol", type=Path)
    parser.add_argument("prior", type=Path, help="combined prepared inputs of the preceding PostgreSQL model")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = fit(args.raw, args.protocol, args.prior, args.out)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"PostgreSQL joint pilot failed: {error}\n")
    print(result["candidate"]["status"])
