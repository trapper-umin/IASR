#!/usr/bin/env python3
"""Retain prior train episodes while preserving the current validation partition byte for byte."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from tempfile import TemporaryDirectory

from prepare_dataset import coverage, group_id
from policy_data import samples
from train_fqi import load_partitions
from validate_episodes import require, read_json, validate_episode


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def restore_prior_train(raw, frozen_manifest, output):
    """Rebuild only frozen train episodes. Excluded dataset files are never opened."""
    raw, frozen_manifest, output = Path(raw), Path(frozen_manifest), Path(output)
    manifest = read_json(frozen_manifest)
    require(manifest.get("preparationVersion") == 1 and not manifest.get("prototypeAllowsDirtySource"), "invalid frozen preparation")
    chosen = {(e["runId"], e["episodeId"]): e for e in manifest["episodes"] if e["split"] == "train"}
    require(chosen, "frozen preparation has no train episodes")
    paths, records = {}, []
    metadata_paths = [raw / "metadata.json"] if (raw / "metadata.json").is_file() else sorted(raw.rglob("metadata.json"))
    for metadata_path in metadata_paths:
        metadata = read_json(metadata_path)
        identity = (metadata["runId"], metadata["episodeId"])
        if identity not in chosen:
            continue
        require(identity not in paths, "duplicate frozen train episode")
        paths[identity] = metadata_path.parent
        entry = chosen[identity]
        require(group_id(metadata) == entry["groupId"] and metadata["source"] == entry["source"], "frozen episode provenance differs")
        validate_episode(metadata_path.parent)
        rows = [json.loads(line) for line in (metadata_path.parent / "dataset.jsonl").read_text().splitlines()]
        built, excluded = samples(metadata, rows)
        require(len(built) == entry["samples"] and excluded == entry["excluded"], "frozen train preparation differs")
        for row in built:
            row.update(runId=identity[0], episodeId=identity[1], groupId=entry["groupId"])
        records.extend(built)
    require(set(paths) == set(chosen), "missing frozen train episodes")
    require(len(records) == manifest["sampleCounts"]["train"], "frozen train count differs")
    records.sort(key=lambda r: (r["groupId"], r["runId"], r["episodeId"], r["sequence"]))
    output.mkdir(parents=True, exist_ok=False)
    (output / "train.jsonl").write_text("".join(json.dumps(r, sort_keys=True, allow_nan=False) + "\n" for r in records))
    (output / "manifest.json").write_bytes(frozen_manifest.read_bytes())


def extend(prior, development, output, prior_raw=None):
    prior, development, output = Path(prior), Path(development), Path(output)
    require(not output.exists(), "output already exists")
    if prior_raw is not None:
        require(prior.is_file(), "raw restoration requires a frozen manifest file")
        with TemporaryDirectory(prefix="iasr-retained-train-") as temporary:
            restored = Path(temporary) / "prepared"
            restore_prior_train(prior_raw, prior, restored)
            return extend(restored, development, output)
    old_manifest, old = load_partitions(prior, splits=("train",))
    current_manifest, current = load_partitions(development, splits=("train", "validation"))
    roles, reserved_episodes = {}, {}
    # Read reservations from metadata, never the excluded partition files.
    for manifest in (old_manifest, current_manifest):
        for key, role in manifest.get("partitionReservations", {}).get("groups", {}).items():
            require(role in ("train", "validation", "test"), "invalid reserved partition")
            require(key not in roles or roles[key] == role, "condition group changed partition")
            roles[key] = role
        for key, group in manifest["groups"].items():
            require(key not in roles or roles[key] == group["split"], "condition group changed partition")
            roles[key] = group["split"]
        for episode in [*manifest.get("partitionReservations", {}).get("episodes", []), *manifest["episodes"]]:
            identity = (episode["runId"], episode["episodeId"])
            require(identity not in reserved_episodes or reserved_episodes[identity] == episode["split"],
                    "episode changed partition")
            reserved_episodes[identity] = episode["split"]
    parts = {"train": old["train"] + current["train"], "validation": current["validation"]}
    episodes, identities, groups = [], set(), {}
    for manifest, selected in ((old_manifest, {"train"}), (current_manifest, {"train", "validation"})):
        for key, group in manifest["groups"].items():
            if group["split"] in selected:
                require(key not in groups or groups[key] == group, "condition definitions differ")
                groups[key] = group
        for episode in manifest["episodes"]:
            if episode["split"] not in selected:
                continue
            identity = (episode["runId"], episode["episodeId"])
            require(identity not in identities, "duplicate episode in retained and current data")
            identities.add(identity)
            episodes.append(episode)
    counts, samples_seen = Counter(), set()
    for split, records in parts.items():
        for row in records:
            identity = (row["runId"], row["episodeId"], row["sequence"])
            require(identity not in samples_seen, "duplicate transition")
            samples_seen.add(identity)
            counts[identity[:2]] += 1
            require(roles[row["groupId"]] == split, "selected group crossed a reserved boundary")
    for episode in episodes:
        require(counts[(episode["runId"], episode["episodeId"])] == episode["samples"], "episode sample count differs")
    parts["train"].sort(key=lambda r: (r["groupId"], r["runId"], r["episodeId"], r["sequence"]))
    sources = [{"role": role, "manifestSha256": digest(path / "manifest.json"), "partitionsRead": splits,
                "partitionSha256": {split: digest(path / (split + ".jsonl")) for split in splits}}
               for role, path, splits in (("retained_train", prior, ["train"]),
                                         ("current_development", development, ["train", "validation"]))]
    reservations = {"groups": dict(sorted(roles.items())),
                    "episodes": [{"runId": run, "episodeId": episode, "split": split}
                                 for (run, episode), split in sorted(reserved_episodes.items())]}
    manifest = {"preparationVersion": 1, "extensionVersion": 1, "historyWindows": 4, "developmentOnly": True,
                "prototypeAllowsDirtySource": False, "sources": sources, "partitionReservations": reservations,
                "groups": dict(sorted(groups.items())), "episodes": sorted(episodes, key=lambda r: (r["runId"], r["episodeId"])),
                "sampleCounts": {name: len(rows) for name, rows in parts.items()}, "testEvaluated": False,
                "validationPolicy": "exact_current_partition_bytes", "validationSha256": digest(development / "validation.jsonl")}
    audits = {name: coverage(rows) for name, rows in parts.items()}
    output.mkdir(parents=True, exist_ok=False)
    (output / "train.jsonl").write_text("".join(json.dumps(r, sort_keys=True, allow_nan=False) + "\n" for r in parts["train"]))
    # Copy exact bytes to prevent silent validation replacement, reordering or numeric reserialization.
    (output / "validation.jsonl").write_bytes((development / "validation.jsonl").read_bytes())
    manifest["partitionSha256"] = {name: digest(output / (name + ".jsonl")) for name in parts}
    (output / "coverage.json").write_text(json.dumps(audits, indent=2, sort_keys=True) + "\n")
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prior", type=Path)
    parser.add_argument("development", type=Path)
    parser.add_argument("--prior-raw", type=Path, help="restore prior train from raw episodes using prior as a frozen manifest")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = extend(args.prior, args.development, args.out, args.prior_raw)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Development extension failed: {error}\n")
    print(json.dumps({"sampleCounts": result["sampleCounts"], "validationSha256": result["validationSha256"], "testEvaluated": False}))
