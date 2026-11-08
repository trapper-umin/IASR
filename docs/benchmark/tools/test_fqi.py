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
from train_fqi import train, load_partitions, build_support, cache_records, bellman_targets, close_training_support


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

    def test_excluded_transitions_cannot_certify_action_support(self):
        directory = self.dataset()
        _, parts = load_partitions(directory)
        rows = copy.deepcopy(parts["train"])
        preprocessing = fit_preprocessor(rows)
        rows[0]["nextHistory"][-1]["state"]["goodput_rps"] = 1e9
        support, cached, excluded = close_training_support(rows, preprocessing, 1, 1, 2)
        self.assertLess(len(cached), len(rows))
        self.assertGreater(excluded.get("unsupported_continuation", 0), 0)
        self.assertEqual(sum(entry["samples"] for actions in support["bins"].values()
                             for entry in actions.values()), len(cached))
        self.assertEqual(support["fittedOn"], "train_bellman_fixed_point")

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

    def test_joint_support_is_checked_after_closure(self):
        from unittest.mock import patch
        from train_fqi import joint_readiness
        directory = self.dataset()
        _, parts = load_partitions(directory)
        retained = []
        for action in ([0, 1], [0, -1], [1, 0]):
            row = copy.deepcopy(parts["train"][0])
            row["action"] = action
            retained.append({"record": row})
        self.assertFalse(joint_readiness([r["record"] for r in retained])["established"])
        with patch("train_fqi.close_training_support", return_value=({}, retained, {})):
            with self.assertRaisesRegex(ValueError, "support closure lacks retained"):
                train(directory, self.root / "model", **self.options())
        self.assertFalse((self.root / "model").exists())

    def test_development_audit_never_opens_test_partition(self):
        from unittest.mock import patch
        from audit_joint_learning import audit
        directory = self.dataset()
        original = Path.read_text
        def development_only(path, *args, **kwargs):
            if path.name == "test.jsonl":
                raise AssertionError("test outcomes must not be read")
            return original(path, *args, **kwargs)
        with patch.object(Path, "read_text", development_only):
            report = audit(directory, min_samples=1, min_episodes=1)
            model, trained = train(directory, self.root / "development-model", development_only=True, **self.options())
            from export_jvm_fixtures import export
            from contextlib import redirect_stdout
            from io import StringIO
            with redirect_stdout(StringIO()):
                export(self.root / "development-model/model.json", directory, self.root / "jvm-fixtures.json")
        self.assertEqual(trained["test"], {"evaluated": False})
        self.assertNotIn("test.jsonl", model["inputSha256"])
        self.assertNotIn("test", trained["counts"])
        self.assertEqual(report["partitionsRead"], ["train", "validation"])
        self.assertFalse(report["testEvaluated"])
        self.assertTrue(report["necessaryJointCoverageEstablished"])

    def test_lag_audit_counts_interventions_without_crossing_episodes(self):
        from audit_joint_learning import lag_descriptions
        directory = self.dataset()
        _, parts = load_partitions(directory)
        records = []
        for sequence, action in enumerate(([0, 1], [1, 0], [0, 0])):
            row = copy.deepcopy(parts["train"][0])
            row.update(sequence=sequence, action=action)
            records.append(row)
        isolated = copy.deepcopy(records[2])
        isolated["episodeId"] = "different-episode"
        report = lag_descriptions([*records[:2], isolated], (3,))
        self.assertFalse(report["groups"])
        report = lag_descriptions(records, (3,))
        self.assertEqual(len(report["groups"]), 1)
        self.assertEqual(next(iter(report["groups"].values()))["limitChangedDuringFollowup"], 1)
        records[1]["action"] = [0, -1]
        report = lag_descriptions(records, (3,))
        self.assertEqual(report["excluded"]["h3:another_pool_change"], 1)

    def test_explicit_development_split_has_no_test_and_keeps_profiles_together(self):
        self.dataset()
        for path in (self.root / "raw").rglob("metadata.json"):
            metadata = json.loads(path.read_text())
            condition = int(path.parent.name.split("-")[0])
            metadata["configuration"]["conditions"]["profile"]["name"] = {0: "steady", 1: "ramp", 2: "spike"}[condition]
            path.write_text(json.dumps(metadata))
        result = prepare(self.root / "raw", self.root / "development", development_only=True, validation_scenarios=("spike",))
        self.assertEqual(set(result["sampleCounts"]), {"train", "validation"})
        self.assertFalse((self.root / "development/test.jsonl").exists())
        for group in result["groups"].values():
            self.assertEqual(group["split"], "validation" if group["conditions"]["profile"]["name"] == "spike" else "train")
        with self.assertRaisesRegex(ValueError, "development-only dataset has no test"):
            load_partitions(self.root / "development")

    def test_audit_preserves_diagnosis_if_support_closure_exhausts_data(self):
        from audit_joint_learning import audit
        directory = self.dataset()
        report = audit(directory, min_samples=1000000)
        self.assertFalse(report["necessaryJointCoverageEstablished"])
        self.assertEqual(report["train"]["retained"], 0)
        self.assertIn("removed every transition", report["train"]["closureFailure"])
        self.assertEqual(report["train"]["closureTrace"][-1]["retained"], 0)

    def test_action_node_audit_checks_supported_branch_comparisons(self):
        from audit_joint_learning import action_node_coverage
        directory = self.dataset()
        _, parts = load_partitions(directory)
        row = next(r for r in parts["train"] if r["history"][-1]["state"]["control_tick"] % 5 == 0)
        preprocessing = fit_preprocessor([row])
        observed = []
        for action in row["candidateActions"]:
            changed = copy.deepcopy(row)
            changed["action"] = action
            observed.append(changed)
        model = {"preprocessing": preprocessing, "support": build_support(observed, preprocessing, 1, 1, 2),
                 "forest": [{"left": [1, -1, -1], "right": [2, -1, -1], "feature": [209, -2, -2],
                              "threshold": [0.5, -2, -2], "value": [0, 0, 1]}]}
        result = action_node_coverage({"validation": [row]}, model)
        self.assertEqual(result["nodes"][0]["partitions"]["validation"],
                         {"historiesReachingNode": 1, "historiesWithSupportedCandidatesOnBothBranches": 1})
        for bucket in model["support"]["bins"].values():
            for key in list(bucket):
                if key.endswith(",1"):
                    del bucket[key]
        result = action_node_coverage({"validation": [row]}, model)
        self.assertEqual(result["nodes"][0]["partitions"]["validation"]["historiesWithSupportedCandidatesOnBothBranches"], 0)

    def test_action_conditioned_regression_distinguishes_actions_at_identical_state(self):
        import numpy as np
        from action_fqi import ActionForestsRegressor
        x, y = [], []
        for dl in (-1, 0, 1):
            for dp in (-1, 0, 1):
                for _ in range(3):
                    x.append([0.] * 208 + [dl, dp])
                    y.append(10 * dl + dp)
        x, y = np.asarray(x, dtype=np.float32), np.asarray(y, dtype=np.float64)
        fitted = ActionForestsRegressor(4, 3, 1, 7, 1.0).fit(x, y)
        np.testing.assert_array_equal(fitted.predict(x[::-1]), y[::-1])
        self.assertEqual(len(fitted.models), 9)
        cached = [{"reward": 2, "record": {"elapsedSeconds": 2}, "nextOptions": [x[0].tolist(), x[-1].tolist()]}]
        self.assertAlmostEqual(bellman_targets(cached, fitted, 0.9)[0], 2 + 0.9 ** 2 * 11)
        fitted.fit(x[:3], y[:3])
        with self.assertRaisesRegex(ValueError, "no trained forest"):
            fitted.predict(x[-1:])
        with self.assertRaisesRegex(ValueError, "normalized discrete"):
            fitted.predict([[0.] * 208 + [0.1, 0]])

    def test_per_action_export_preserves_support_and_covers_every_action_in_goldens(self):
        from fqi_runtime import predict_model, normalized_action_key
        directory = self.dataset()
        pooled, _ = train(directory, self.root / "pooled", **self.options())
        model, report = train(directory, self.root / "routed", regressor="per_action", **self.options())
        self.assertEqual(model["modelVersion"], 2)
        self.assertEqual(model["stateFeatureCount"], 208)
        self.assertNotIn("forest", model)
        self.assertEqual(model["preprocessing"], pooled["preprocessing"])
        self.assertEqual(model["support"], pooled["support"])
        self.assertEqual(sum(model["trainingConfig"]["actionTrainingCounts"].values()), report["counts"]["train"]["scored"])
        golden = json.loads((self.root / "routed/prediction-fixtures.json").read_text())
        self.assertEqual({normalized_action_key(r["features"][-2:]) for r in golden}, set(model["actionForests"]))
        for row in golden:
            self.assertAlmostEqual(predict_model(model, row["features"]), row["prediction"], places=12)
        self.assertEqual(model, load_model(self.root / "routed/model.json"))
        for forest in model["actionForests"].values():
            self.assertTrue(all(f == -2 or 0 <= f < 208 for tree in forest for f in tree["feature"]))
        self.assertLessEqual(report["portableExportMaxError"], 1e-10)

    def test_per_action_development_fit_does_not_use_holdout_values(self):
        from unittest.mock import patch
        directory = self.dataset()
        original = Path.read_text
        def no_test(path, *args, **kwargs):
            if path.name == "test.jsonl":
                raise AssertionError("test outcomes must remain unopened")
            return original(path, *args, **kwargs)
        with patch.object(Path, "read_text", no_test):
            first, _ = train(directory, self.root / "first-action", regressor="per_action", development_only=True, **self.options())
            path = directory / "validation.jsonl"
            records = [json.loads(line) for line in path.read_text().splitlines()]
            for row in records:
                for history in (row["history"], row["nextHistory"]):
                    for observation in history:
                        observation["state"]["goodput_rps"] = 1e6
            path.write_text("".join(json.dumps(row) + "\n" for row in records))
            second, _ = train(directory, self.root / "second-action", regressor="per_action", development_only=True, **self.options())
        for key in ("preprocessing", "support", "actionForests"):
            self.assertEqual(first[key], second[key])
        self.assertNotIn("test.jsonl", first["inputSha256"])

    def test_normalized_action_routing_handles_window_step_and_rejects_unknown_actions(self):
        from fqi_features import action_features
        from fqi_runtime import predict_model
        model = {"modelVersion": 2, "algorithm": "masked_fqi_action_forests", "actionForests": {
            "1,-1": [{"left": [-1], "right": [-1], "feature": [-2], "threshold": [-2], "value": [7]}]}}
        self.assertEqual(predict_model(model, action_features([0] * 208, [2, -1], {"limitStep": 2})), 7)
        with self.assertRaisesRegex(ValueError, "no trained forest"):
            predict_model(model, [0] * 210)

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
