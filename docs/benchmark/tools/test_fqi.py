import copy
import json
import tempfile
import unittest
from pathlib import Path

from test_prepare_dataset import fixture
from prepare_dataset import prepare
from policy_data import candidates, LIMIT, POOL
from fqi_features import fit_preprocessor, transform, reward, FEATURE_NAMES
from fqi_runtime import supported_actions, predict_forest, decide, load_model
from train_fqi import train, load_partitions, build_support, cache_records, bellman_targets


class FqiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def dataset(self, joint=True):
        for condition in range(3):
            for repetition in range(1, 4):
                path, metadata, rows = fixture(self.root / "raw", condition=condition, repetition=repetition)
                resources = {LIMIT: 10, POOL: 5}
                for row in rows:
                    step = row["sequence"]
                    row["resourcesBefore"] = resources.copy()
                    dl, dp = 0, 0
                    if joint and step >= 4:
                        if step % 5 == 0:
                            dl, dp = (-1, 1) if step == 5 else (1, -1)
                        else:
                            dl = (step % 3) - 1
                    resources = {LIMIT: resources[LIMIT] + dl, POOL: resources[POOL] + dp}
                    row["action"] = resources.copy()
                    row["state"].update(goodput_rps=100 + condition * 10 + step,
                                        cpu_proc=0.2 + 0.01 * step)
                for a, b in zip(rows, rows[1:]):
                    a["outcome"] = copy.deepcopy(b["state"])
                (path / "dataset.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
        prepare(self.root / "raw", self.root / "prepared", seed=42)
        return self.root / "prepared"

    @staticmethod
    def options():
        return dict(iterations=2, trees=4, depth=3, leaf=1, seed=7, min_samples=1, min_episodes=1)

    def test_portable_model_is_deterministic_and_can_be_reloaded(self):
        directory = self.dataset()
        model, report = train(directory, self.root / "model", **self.options())
        second, _ = train(directory, self.root / "model-again", **self.options())
        self.assertEqual(model, second)
        self.assertLessEqual(report["portableExportMaxError"], 1e-10)
        self.assertFalse(model["deploymentAllowed"])
        self.assertFalse(report["policyPerformanceEstablished"])
        self.assertEqual(model, load_model(self.root / "model" / "model.json"))
        _, parts = load_partitions(directory)
        row = parts["train"][0]
        decision = decide(model, row["history"], row["context"])
        self.assertFalse(decision["fallback"])
        self.assertIn(decision["action"], row["candidateActions"])
        with self.assertRaises(ValueError):
            train(directory, self.root / "model", **self.options())

    def test_validation_and_test_values_do_not_change_the_fitted_model(self):
        directory = self.dataset()
        first, _ = train(directory, self.root / "model", **self.options())
        for split in ("validation", "test"):
            path = directory / f"{split}.jsonl"
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            for row in rows:
                for history in (row["history"], row["nextHistory"]):
                    for observation in history:
                        observation["state"]["goodput_rps"] = 1_000_000
            path.write_text("".join(json.dumps(r) + "\n" for r in rows))
        second, _ = train(directory, self.root / "changed-holdout-model", **self.options())
        for key in ("preprocessing", "support", "forest"):
            self.assertEqual(first[key], second[key])

    def test_missing_masks_and_absolute_tick_do_not_leak_scenario_time(self):
        directory = self.dataset()
        _, parts = load_partitions(directory)
        row = parts["train"][0]
        preprocessing = fit_preprocessor(parts["train"])
        expected = transform(row["history"], row["context"], preprocessing)
        changed = copy.deepcopy(row["history"])
        for observation in changed:
            observation["state"]["control_tick"] += 1000  # Same pool phase, very different elapsed scenario time.
        self.assertEqual(expected, transform(changed, row["context"], preprocessing))
        changed[0]["state"]["cpu_proc"] = None
        converted = transform(changed, row["context"], preprocessing)
        index = FEATURE_NAMES.index("w0.cpu_proc")
        self.assertEqual(converted[index], 0.0)
        self.assertEqual(converted[index + len(FEATURE_NAMES) // 2], 1.0)

    def test_unknown_continuation_is_excluded_instead_of_assumed_terminal(self):
        directory = self.dataset()
        _, parts = load_partitions(directory)
        preprocessing = fit_preprocessor(parts["train"])
        support = build_support(parts["train"], preprocessing, 1, 1, 2)
        row = copy.deepcopy(parts["train"][0])
        row["nextHistory"][-1]["state"]["latency_p95_ms"] = 500
        row["nextCandidateActions"] = candidates(row["nextHistory"][-1], row["context"])
        cached, excluded = cache_records([row], preprocessing, support)
        self.assertFalse(cached)
        self.assertEqual(excluded.get("unsupported_continuation"), 1)

    def test_future_value_is_discounted_by_actual_seconds(self):
        import numpy as np
        class ConstantQ:
            def predict(self, x):
                return np.full(len(x), 3.0)
        cached = [{"reward": 2, "record": {"elapsedSeconds": 2}, "nextOptions": [[0], [1]]}]
        self.assertAlmostEqual(bellman_targets(cached, ConstantQ(), 0.9)[0], 2 + 0.9 ** 2 * 3)

    def test_reward_excludes_shedding_from_goodput_and_penalizes_resource_changes(self):
        directory = self.dataset()
        _, parts = load_partitions(directory)
        row = copy.deepcopy(parts["train"][0])
        row["action"] = [1, 1]
        row["nextHistory"][-1]["state"].update(goodput_rps=100, latency_p95_ms=300, error_rate=0.1, timeout_rate=0.2)
        expected = 1 - 2 * (300 / 150 - 1) - 0.2 - 2 * 0.1 - 0.05 * (1/19 + 1/9)
        self.assertAlmostEqual(reward(row, {"rref": 100}), expected)

    def test_out_of_domain_observation_falls_back(self):
        directory = self.dataset()
        model, _ = train(directory, self.root / "model", **self.options())
        _, parts = load_partitions(directory)
        row = copy.deepcopy(parts["train"][0])
        for observation in row["history"]:
            observation["state"]["goodput_rps"] = 1e9
        self.assertTrue(decide(model, row["history"], row["context"])["fallback"])

    def test_export_matches_float32_split_semantics(self):
        forest = [{"left": [1, -1, -1], "right": [2, -1, -1], "feature": [0, -2, -2],
                   "threshold": [0.1, -2, -2], "value": [0, 5, 7]}]
        self.assertEqual(predict_forest(forest, [0.1]), 7.0)  # float32(0.1) > double(0.1).

    def test_data_without_pool_actions_cannot_train_a_joint_policy(self):
        directory = self.dataset(joint=False)
        with self.assertRaisesRegex(ValueError, "pool growth"):
            train(directory, self.root / "model", **self.options())

    def test_tampered_partition_is_rejected(self):
        directory = self.dataset()
        path = directory / "test.jsonl"
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        manifest = json.loads((directory / "manifest.json").read_text())
        rows[0]["groupId"] = next(k for k, v in manifest["groups"].items() if v["split"] == "train")
        path.write_text("".join(json.dumps(r) + "\n" for r in rows))
        with self.assertRaisesRegex(ValueError, "partition"):
            load_partitions(directory)


if __name__ == "__main__":
    unittest.main()
