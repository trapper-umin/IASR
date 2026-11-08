import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_prepare_dataset import fixture
from prepare_dataset import prepare
from extend_development_dataset import extend
from select_development_model import select, GRID
from train_fqi import load_partitions


class DevelopmentExtensionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def source(self, name, offset, development=False):
        profiles = ("steady", "ramp", "spike") if development else ("steady", "ramp", "slow-dependency")
        for index, profile in enumerate(profiles):
            path, metadata, _ = fixture(self.root / (name + "-raw"), condition=offset + index)
            metadata["configuration"]["conditions"]["profile"]["name"] = profile
            (path / "metadata.json").write_text(json.dumps(metadata))
        out = self.root / name
        prepare(self.root / (name + "-raw"), out, development_only=development,
                validation_scenarios=("spike",) if development else ())
        return out

    def test_retains_train_without_reading_excluded_outcomes_and_freezes_validation(self):
        prior, current = self.source("prior", 0), self.source("current", 10, True)
        validation = current / "validation.jsonl"
        validation.write_text("".join("  " + line + "\n" for line in validation.read_text().splitlines()))
        expected = validation.read_bytes()
        original = Path.read_text
        def restricted(path, *args, **kwargs):
            if path.name == "test.jsonl" or path == prior / "validation.jsonl":
                raise AssertionError("excluded outcomes must never be opened")
            return original(path, *args, **kwargs)
        with patch.object(Path, "read_text", restricted):
            manifest = extend(prior, current, self.root / "extended")
            _, parts = load_partitions(self.root / "extended", splits=("train", "validation"))
        self.assertEqual(manifest["sampleCounts"], {"train": 21, "validation": 7})
        self.assertEqual((self.root / "extended/validation.jsonl").read_bytes(), expected)
        self.assertFalse((self.root / "extended/test.jsonl").exists())
        old = json.loads((prior / "manifest.json").read_text())
        self.assertTrue(all(manifest["partitionReservations"]["groups"][key] == group["split"] for key, group in old["groups"].items()))
        self.assertEqual(len(parts["train"]), 21)
        self.assertEqual(manifest["sources"][0]["partitionsRead"], ["train"])

    def test_raw_restoration_never_opens_excluded_episode_datasets(self):
        prior, current = self.source("prior", 0), self.source("current", 10, True)
        manifest = json.loads((prior / "manifest.json").read_text())
        chosen = {e["episodeId"] for e in manifest["episodes"] if e["split"] == "train"}
        original = Path.read_text
        def restricted(path, *args, **kwargs):
            if path.name == "dataset.jsonl" and path.parent.parent == self.root / "prior-raw" and path.parent.name not in chosen:
                raise AssertionError("excluded raw outcomes must never be opened")
            return original(path, *args, **kwargs)
        with patch.object(Path, "read_text", restricted):
            first = extend(prior / "manifest.json", current, self.root / "restored", prior_raw=self.root / "prior-raw")
        second = extend(prior, current, self.root / "prepared-extension")
        self.assertEqual(first, second)
        self.assertEqual((self.root / "restored/train.jsonl").read_bytes(), (self.root / "prepared-extension/train.jsonl").read_bytes())

    def test_refuses_to_reassign_a_previously_held_out_condition(self):
        prior = self.source("prior", 0)
        previous = json.loads((prior / "manifest.json").read_text())
        reserved = next(g["conditions"] for g in previous["groups"].values() if g["split"] == "test")
        for index, profile in enumerate(("steady", "ramp", "spike")):
            path, metadata, _ = fixture(self.root / "current-raw", condition=100 + index)
            metadata["configuration"]["conditions"]["profile"]["name"] = profile
            if index == 0:
                metadata["configuration"]["conditions"] = copy.deepcopy(reserved)
            (path / "metadata.json").write_text(json.dumps(metadata))
        current = self.root / "current"
        prepare(self.root / "current-raw", current, development_only=True, validation_scenarios=("spike",))
        with self.assertRaisesRegex(ValueError, "condition group changed partition"):
            extend(prior, current, self.root / "bad")
        self.assertFalse((self.root / "bad").exists())

    def test_duplicate_episodes_and_tampered_episode_counts_are_rejected(self):
        prior, current = self.source("prior", 0), self.source("current", 10, True)
        with self.assertRaisesRegex(ValueError, "duplicate episode"):
            extend(prior, prior, self.root / "duplicate")
        path = prior / "manifest.json"
        manifest = json.loads(path.read_text())
        next(e for e in manifest["episodes"] if e["split"] == "train")["samples"] += 1
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "episode sample count differs"):
            extend(prior, current, self.root / "tampered")
        self.assertFalse((self.root / "duplicate").exists())
        self.assertFalse((self.root / "tampered").exists())

    def test_frozen_validation_cannot_be_modified_after_extension(self):
        prior, current = self.source("prior", 0), self.source("current", 10, True)
        output = self.root / "frozen"
        extend(prior, current, output)
        path = output / "validation.jsonl"
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[0]["history"][0]["state"]["goodput_rps"] += 1
        path.write_text("".join(json.dumps(row) + "\n" for row in rows))
        with self.assertRaisesRegex(ValueError, "frozen partition content differs"):
            load_partitions(output, splits=("train", "validation"))

    def fake_train(self, rmses, counts=None):
        def fit(directory, output, **options):
            self.assertTrue(options["development_only"])
            output.mkdir()
            (output / "model.json").write_text(json.dumps({"id": output.name}))
            score = (counts or {}).get(output.name, 10)
            return ({"inputSha256": {"train.jsonl": "train", "validation.jsonl": "validation"}},
                    {"config": options, "trace": [{"validation": {"scored": score, "bellmanRmse": rmses[output.name]}}]})
        return fit

    def test_selection_uses_predeclared_metric_not_last_model_and_excludes_test(self):
        with patch("select_development_model.train", side_effect=self.fake_train({"reference": 1.2, "deeper": 0.8, "subspace": 1.0})) as fitted:
            report = select(self.root, self.root / "selection")
        self.assertEqual(report["selected"], "deeper")
        self.assertFalse(report["testEvaluated"])
        self.assertEqual([call.kwargs["depth"] for call in fitted.call_args_list], [s["depth"] for s in GRID])
        self.assertEqual([call.kwargs["max_features"] for call in fitted.call_args_list], [s["max_features"] for s in GRID])
        self.assertTrue(all(call.kwargs["development_only"] for call in fitted.call_args_list))

    def test_selection_refuses_unequal_coverage_and_has_deterministic_ties(self):
        metrics = {"reference": 1, "deeper": 1, "subspace": 1}
        with patch("select_development_model.train", side_effect=self.fake_train(metrics, {"subspace": 9})):
            with self.assertRaisesRegex(ValueError, "different validation coverage"):
                select(self.root, self.root / "bad-selection")
        self.assertFalse((self.root / "bad-selection/selection.json").exists())
        with patch("select_development_model.train", side_effect=self.fake_train(metrics)):
            self.assertEqual(select(self.root, self.root / "tie")["selected"], "reference")
        with patch("select_development_model.train", side_effect=self.fake_train(metrics, {s["id"]: 0 for s in GRID})):
            self.assertIsNone(select(self.root, self.root / "empty")["selected"])
