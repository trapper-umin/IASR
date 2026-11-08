import copy
import json
import tempfile
import unittest
from pathlib import Path
from audit_controller_comparison import audit, classify_actions


class ControllerComparisonTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[1]
    RAW = ROOT / "raw/retained-train-control-2026-11-08"
    MODELS = ROOT / "models/fqi-retained-train-2026-11-08"
    PLAN = ROOT / "analysis/retained-train-2026-11-08/technical-check-plan.json"

    def test_real_comparison_replays_all_model_actions_without_attributing_baseline(self):
        report = audit(self.RAW, self.MODELS, self.PLAN)
        self.assertEqual(report["integrity"]["records"], 179)
        self.assertEqual(sum(c["modelDecisions"] for c in report["cases"]), 160)
        self.assertTrue(all(c["modelPoolChanges"] == c["modelLimitChanges"] == 0 for c in report["cases"]))
        self.assertEqual(report["selectionUnchanged"], "subspace")
        reference = [c for c in report["cases"] if c["arm"] == "reference"]
        self.assertGreater(sum(c["modelOnLiveObservations"]["supportedPoolOpportunities"] for c in reference), 0)
        for case in reference:
            for node in case["actionNodesOnLiveObservations"]["nodes"]:
                if node["actionFeature"] == "action.pool":
                    self.assertEqual(node["partitions"]["live"]["historiesReachingNode"], 0)

    def test_action_conditioned_policy_changes_both_resources_without_counting_fallback(self):
        raw = self.ROOT / "raw/action-conditioned-control-2026-11-08"
        models = self.ROOT / "models/fqi-action-conditioned-2026-11-08"
        report = audit(raw, models, models / "technical-check-plan.json")
        self.assertIsNone(report["selectionUnchanged"])
        self.assertEqual(report["integrity"]["records"], 180)
        experimental = [c for c in report["cases"] if c["arm"] == "experimental"]
        self.assertEqual(sum(c["modelDecisions"] for c in experimental), 78)
        self.assertEqual(sum(c["modelLimitChanges"] for c in experimental), 30)
        self.assertEqual(sum(c["modelPoolChanges"] for c in experimental), 12)
        self.assertEqual(sum(c["modelSimultaneousChanges"] for c in experimental), 2)
        for case in experimental:
            self.assertEqual(case["actionNodesOnLiveObservations"]["routing"], "per_normalized_joint_action")
        self.assertTrue(all(c["modelPoolChanges"] == 0 for c in report["cases"] if c["arm"] == "reference"))

    def test_changed_metric_winner_is_rejected_before_comparison(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            execution = json.loads((self.RAW / "execution.json").read_text())
            execution["plan"]["selectionUnchanged"] = "reference"
            execution["plan"]["arms"]["selected"]["candidate"] = "reference"
            (root / "execution.json").write_text(json.dumps(execution))
            plan = root / "plan.json"
            plan.write_text(json.dumps(execution["plan"]))
            with self.assertRaisesRegex(ValueError, "metric winner was replaced"):
                audit(root, self.MODELS, plan)

    def test_method_comparison_roles_cannot_replace_the_registered_experiment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            execution = json.loads((self.RAW / "execution.json").read_text())
            execution["plan"]["selectionPolicy"] = "predeclared_method_comparison"
            (root / "execution.json").write_text(json.dumps(execution))
            plan = root / "plan.json"; plan.write_text(json.dumps(execution["plan"]))
            with self.assertRaisesRegex(ValueError, "invalid method comparison roles"):
                audit(root, self.MODELS, plan)

    def test_baseline_joint_change_is_not_a_model_action(self):
        path = next((self.RAW / "reference/steady").rglob("dataset.jsonl"))
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        fallback = next(r for r in rows if r["action"]["hikari_max_pool"] != r["resourcesBefore"]["hikari_max_pool"])
        modeled = next(r for r in rows if r["reasons"]["concurrency_limit"].startswith("learned_policy"))
        result = classify_actions([fallback, modeled])
        self.assertEqual(result["modelDecisions"], 1)
        self.assertEqual(result["modelPoolChanges"], 0)
        self.assertEqual(sum(result["fallbackActions"].values()), 1)
        altered = copy.deepcopy(modeled)
        altered["reasons"]["hikari_max_pool"] = "learned_fallback_budget"
        with self.assertRaisesRegex(ValueError, "inconsistent controller provenance"):
            classify_actions([altered])
