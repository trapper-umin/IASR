import json
import math
import tempfile
import unittest
from pathlib import Path

import numpy as np

from iasr_learning import dataset, features as F, fqi, runtime

RULES = [{"actuatorName": "concurrency_limit", "minValue": 2, "maxValue": 400, "maxStep": 20, "cooldownTicks": 1,
          "blockIncreaseOnSloViolation": True, "maxStepDown": 50},
         {"actuatorName": "hikari_max_pool", "minValue": 2, "maxValue": 16, "maxStep": 2, "cooldownTicks": 2,
          "blockIncreaseOnSloViolation": False, "maxStepDown": 0}]
LEVELS = [r["levels"] for r in dataset.DEFAULT_RESOURCES]


def window(tick, limit, pool, p95=60.0, goodput=50.0, rejected=0.0, within=1.0, pending=0, action=None):
    state = {"latency_p95_ms": p95, "latency_mean_ms": p95 * 0.8, "goodput_rps": goodput, "timeout_rate": rejected,
             "error_rate": 0.0, "slo_good_fraction": within, "cpu_proc": 0.1, "inflight": min(limit, 4),
             "queue_length": 0, "admission_wait_mean_ms": 0.0, "service_mean_ms": p95 * 0.7,
             "hikari_active": min(pool, 3), "hikari_pending": pending, "hikari_acquire_mean_ms": 0.0,
             "dependency_mean_ms": p95 * 0.6, "control_tick": tick, "control_window_sec": 1,
             "observation_elapsed_sec": 1.001 if tick else None}
    before = {"concurrency_limit": limit, "hikari_max_pool": pool}
    return {"state": state, "resourcesBefore": before, "cooldownRemaining": {"concurrency_limit": 0, "hikari_max_pool": 0},
            "action": action or dict(before)}


def episode(rows):
    return {"configuration": {"sloMs": 150.0, "guardrails": RULES}, "episodeId": "e1", "scenario": "s", "mode": "m"}, rows


class FeatureContract(unittest.TestCase):
    def test_names_match_vector_sizes(self):
        resources = dataset.DEFAULT_RESOURCES
        smoother = F.Smoother(resources, 150)
        state = smoother.observe(window(1, 10, 4)["state"], [10, 4])
        self.assertEqual(len(state), len(F.state_feature_names(resources)))
        self.assertEqual(len(smoother.action_features([10, 4], [11, 5])), len(F.action_feature_names(resources)))

    def test_average_starts_at_first_value_and_moves_by_alpha(self):
        smoother = F.Smoother(dataset.DEFAULT_RESOURCES, 150)
        first = smoother.observe(window(1, 10, 4, p95=150)["state"], [10, 4])
        second = smoother.observe(window(2, 10, 4, p95=300)["state"], [10, 4])
        self.assertEqual(first[1], 1.0)
        self.assertAlmostEqual(second[1], 1.0 + F.ALPHA * 1.0)
        self.assertEqual(second[0], 2.0)

    def test_action_features_describe_the_configuration_left_behind(self):
        smoother = F.Smoother(dataset.DEFAULT_RESOURCES, 150)
        smoother.observe(window(1, 10, 4)["state"], [10, 4])
        action = smoother.action_features([10, 4], [8, 5])
        self.assertAlmostEqual(action[0], -0.2)
        self.assertEqual(action[1], 8.0)
        self.assertAlmostEqual(action[4], 0.25)
        self.assertAlmostEqual(action[-1], 5 / 8)

    def test_missing_and_non_finite_signals_fall_back(self):
        self.assertEqual(F.number(None), 0.0)
        self.assertEqual(F.number(float("nan"), 1.0), 1.0)
        self.assertEqual(F.served_within_slo({"timeout_rate": 0.25, "error_rate": 0.0}), 0.75)
        self.assertEqual(F.served_within_slo({"timeout_rate": 0.9, "error_rate": 0.4}), 0.0)


class ActionSpace(unittest.TestCase):
    def test_relative_levels(self):
        self.assertEqual(F.level_delta([-0.25, 2], 40), -10)
        self.assertEqual(F.level_delta([-0.25, 2], 5), -2)
        self.assertEqual(F.level_delta([0.1, 1], 25), 3)
        self.assertEqual(F.level_delta([0, 0], 25), 0)

    def test_candidates_mirror_the_java_space(self):
        joint = F.candidates(RULES, LEVELS, [40, 8], [0, 0], 100.0, 150.0)
        self.assertEqual(len(joint), 15)
        self.assertEqual(joint[0], [40, 8])
        self.assertIn([30, 9], joint)
        blocked = F.candidates(RULES, LEVELS, [40, 8], [0, 0], 400.0, 150.0)
        self.assertTrue(all(c[0] <= 40 for c in blocked) and any(c[1] == 9 for c in blocked))
        self.assertEqual(len(F.candidates(RULES, LEVELS, [40, 8], [0, 1], 100.0, 150.0)), 5)
        self.assertEqual(F.targets(RULES[0], LEVELS[0], 3, 0, 100.0, 150.0), [3, 2, 4, 5])
        self.assertEqual(F.targets(RULES[1], LEVELS[1], 16, 0, 100.0, 150.0), [16, 14])


class Transitions(unittest.TestCase):
    def test_reward_is_the_share_served_within_the_objective_less_a_move_price(self):
        nxt = {"timeout_rate": 0.2, "error_rate": 0.0, "slo_good_fraction": 0.5}
        self.assertAlmostEqual(dataset.reward(nxt, [10, 4], [10, 4]), 0.4)
        self.assertAlmostEqual(dataset.reward(nxt, [10, 4], [8, 5]), 0.4 - dataset.MOVE_COST * (0.2 + 0.25))

    def test_history_restarts_after_idle_irregular_or_skipped_windows(self):
        rows = [window(t, 10, 4) for t in range(0, 8)]
        rows[4]["state"]["goodput_rps"] = 0.0            # idle window
        points = list(dataset.decision_points(*episode(rows)))
        self.assertEqual([p["index"] for p in points], [2, 3, 6, 7])
        rows = [window(t, 10, 4) for t in range(0, 6)]
        rows[3]["state"]["observation_elapsed_sec"] = 1.9  # stretched window
        self.assertEqual([p["index"] for p in dataset.decision_points(*episode(rows))], [2, 5])

    def test_transitions_join_consecutive_points_only(self):
        rows = [window(t, 10, 4) for t in range(0, 8)]
        rows[2]["action"] = {"concurrency_limit": 9, "hikari_max_pool": 5}
        rows[4]["state"]["goodput_rps"] = 0.0
        result = dataset.transitions(*episode(rows))
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["direction"], "-1,1")
        self.assertEqual(len(result[0]["nextCandidates"]), 15)


def synthetic_samples(count=400, seed=3):
    """A world where shrinking an oversized pool pays and growing it costs."""
    rng = np.random.default_rng(seed)
    rows, tick, limit, pool = [], 0, 12, 8
    for _ in range(count):
        tick += 1
        move = int(rng.integers(-1, 2))
        target = min(16, max(2, pool + move))
        p95 = 60.0 + 25.0 * max(0, pool - 4) + float(rng.normal(0, 3))
        rows.append(window(tick, limit, pool, p95=p95, within=1.0 if p95 <= 150 else 0.4,
                           action={"concurrency_limit": limit, "hikari_max_pool": target}))
        pool = target
    return episode(rows)


class FittedPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.metadata, cls.rows = synthetic_samples()
        cls.samples = dataset.transitions(cls.metadata, cls.rows)
        cls.model, cls.counts, cls.config, cls.trace = fqi.fit(cls.samples, iterations=6, trees=12, minLeaf=5, minSupport=3)
        cls.policy = json.loads(fqi.dumps(fqi.export(cls.model, cls.counts, cls.config)))

    def test_training_is_reproducible_to_the_byte(self):
        again = fqi.fit(self.samples, iterations=6, trees=12, minLeaf=5, minSupport=3)
        self.assertEqual(fqi.dumps(fqi.export(again[0], again[1], again[2])), fqi.dumps(self.policy))

    def test_portable_runtime_equals_scikit_learn(self):
        x = np.asarray([s["state"] + s["action"] for s in self.samples[:50]], dtype=np.float32)
        expected = self.model.predict(x)
        for row, value in zip(self.samples[:50], expected):
            self.assertAlmostEqual(runtime.predict_policy(self.policy, row["state"] + row["action"]), float(value), places=10)
        self.assertIn("valueForest", self.policy)

    def test_single_regressor_remains_available(self):
        model, counts, config, _ = fqi.fit(self.samples, iterations=3, trees=6, minLeaf=5, minSupport=3,
                                           decomposition="joint")
        policy = json.loads(fqi.dumps(fqi.export(model, counts, config)))
        self.assertNotIn("valueForest", policy)
        row = self.samples[0]
        x = np.asarray([row["state"] + row["action"]], dtype=np.float32)
        self.assertAlmostEqual(runtime.predict_policy(policy, row["state"] + row["action"]), float(model.predict(x)[0]), places=10)

    def test_policy_learns_to_shrink_an_oversized_pool(self):
        shrink = hold_or_grow = 0
        bold = {**self.policy, "confidence": 0.0}
        for point in dataset.decision_points(self.metadata, self.rows):
            if point["capacities"][1] >= 9 and len(point["candidates"]) > 1:
                chosen, _, reason = runtime.decide(bold, point)
                if chosen is not None and chosen[1] < point["capacities"][1]:
                    shrink += 1
                else:
                    hold_or_grow += 1
        self.assertGreater(shrink, 3 * max(1, hold_or_grow))

    def test_unsupported_states_keep_the_rules_proposal(self):
        point = next(iter(dataset.decision_points(self.metadata, self.rows)))
        proposal = [point["capacities"][0] - 1, point["capacities"][1]]
        chosen, _, reason = runtime.decide({**self.policy, "confidence": 0.0},
                                           {**point, "bin": "never/seen/before", "reference": proposal})
        self.assertEqual(chosen, proposal)
        self.assertEqual(reason, "baseline")

    def test_reference_follows_the_guardrail_chain(self):
        rule = RULES[0]
        self.assertEqual(F.clamp(rule, 40, 10, 0, 100.0, 150.0), 10)
        self.assertEqual(F.clamp(rule, 40, 90, 0, 100.0, 150.0), 60)
        self.assertEqual(F.clamp(rule, 40, 45, 0, 400.0, 150.0), 40)
        self.assertEqual(F.clamp(rule, 40, 30, 1, 100.0, 150.0), 40)
        self.assertEqual(F.clamp(rule, 3, 1, 0, 100.0, 150.0), 2)

    def test_confidence_bound_makes_the_policy_more_cautious(self):
        points = list(dataset.decision_points(self.metadata, self.rows))
        def moves(kappa):
            policy = {**self.policy, "confidence": kappa}
            return sum(1 for p in points if runtime.decide(policy, p)[0] != p["reference"])
        self.assertGreaterEqual(moves(0.0), moves(1.0))
        self.assertGreaterEqual(moves(1.0), moves(5.0))
        self.assertGreater(moves(0.0), 0)

    def test_margin_keeps_the_policy_from_moving_for_nothing(self):
        cautious = {**self.policy, "margin": 1e9}
        for point in list(dataset.decision_points(self.metadata, self.rows))[:40]:
            chosen, _, reason = runtime.decide(cautious, point)
            self.assertEqual(chosen, point["reference"])


if __name__ == "__main__":
    unittest.main()
