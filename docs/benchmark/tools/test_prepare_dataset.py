import copy
import json
import tempfile
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

from collect_dataset import commands, collect
from policy_data import candidates, context, samples, observation, LIMIT, POOL
from prepare_dataset import prepare, group_id, partition_groups, coverage


def fixture(root, condition=1, mode="explore", repetition=1, dirty=False):
    name = f"{condition}-{mode}-{repetition}"
    path = Path(root) / name
    path.mkdir(parents=True)
    rule = lambda key, lower, upper: {"actuatorName": key, "minValue": lower, "maxValue": upper,
                                    "maxStep": 5, "maxStepDown": 0, "cooldownTicks": 0,
                                    "blockIncreaseOnSloViolation": key == LIMIT}
    metadata = {"metadataVersion": 1, "datasetSchemaVersion": 3, "status": "complete",
                "runId": "run-1", "episodeId": name, "mode": mode, "repetition": repetition,
                "orderSeed": 42, "source": {"commit": "abc", "dirty": dirty}, "datasetRecords": 14,
                "timing": {"measurementStartedAt": "2026-11-08T10:00:01Z",
                           "measurementScheduledEndAt": "2026-11-08T10:00:12Z",
                           "captureEndedAt": "2026-11-08T10:00:14Z"},
                "configuration": {"iasr": {"windowMs": 1000}, "sloMs": 150,
                                  "guardrails": [rule(LIMIT, 1, 20), rule(POOL, 1, 10)],
                                  "conditions": {"profile": {"name": "steady", "loadScale": condition}}}}
    at = lambda step: f"2026-11-08T10:00:{step:02d}Z"
    rows = []
    for step in range(14):
        state = {"goodput_rps": 100, "latency_p95_ms": 50, "control_tick": step, "control_window_sec": 1,
                 "inflight": 1, "hikari_pending": 0, "error_rate": 0, "timeout_rate": 0}
        rows.append({"schemaVersion": 3, "sequence": step, "timestamp": at(step), "windowSec": 1,
                     "stateWindowStartedAt": at(step - 1) if step else None,
                     "outcomeTimestamp": at(step + 1) if step < 13 else None,
                     "state": state, "resourcesBefore": {LIMIT: 10, POOL: 5}, "action": {LIMIT: 10, POOL: 5},
                     "cooldownRemaining": {LIMIT: 0, POOL: 0}, "intent": {}, "outcome": None})
    for a, b in zip(rows, rows[1:]):
        a["outcome"] = copy.deepcopy(b["state"])
    (path / "metadata.json").write_text(json.dumps(metadata))
    (path / "dataset.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
    (path / "result.json").write_text("{}")
    (path / "actuators.csv").write_text("t_sec,concurrency_limit,hikari_max_pool,inflight\n")
    return path, metadata, rows


class PolicyDataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_live_observation_uses_monotonic_interval_and_archives_keep_wall_time(self):
        _, meta, rows = fixture(self.root)
        row = rows[5]
        self.assertEqual(observation(row)["elapsedSeconds"], 1)
        row["state"]["observation_elapsed_sec"] = 1.012345678
        self.assertEqual(observation(row)["elapsedSeconds"], 1.012345678)
        row["state"]["observation_elapsed_sec"] = -1
        with self.assertRaises(ValueError):
            observation(row)
        meta["configuration"]["learned"] = {"poolPeriodTicks": 7}
        self.assertEqual(context(meta)["poolPeriodTicks"], 7)

    def test_masks_match_the_runtime_contract(self):
        _, meta, rows = fixture(self.root)
        row = rows[5]
        ctrl = context(meta)
        self.assertEqual(len(candidates(row, ctrl)), 9)
        row["state"]["control_tick"] = 6
        self.assertEqual(candidates(row, ctrl), [[-1, 0], [0, 0], [1, 0]])
        row["state"]["control_tick"] = 5
        row["state"]["latency_p95_ms"] = 200
        self.assertEqual(len(candidates(row, ctrl)), 6)
        row["cooldownRemaining"][LIMIT] = 2
        self.assertEqual(candidates(row, ctrl), [[0, -1], [0, 0], [0, 1]])

    def test_history_excludes_warmup_future_and_episode_identity(self):
        _, meta, rows = fixture(self.root)
        rows[6]["state"]["goodput_rps"] = 9999
        rows[5]["state"]["future_label"] = 99999
        records, _ = samples(meta, rows)
        first = next(r for r in records if r["sequence"] == 5)
        self.assertEqual(len(first["history"]), 4)
        self.assertEqual(first["history"][-1]["state"]["goodput_rps"], 100)
        self.assertEqual(first["nextHistory"][-1]["state"]["goodput_rps"], 9999)
        self.assertNotIn("future_label", first["history"][-1]["state"])
        self.assertNotIn("episodeId", first["history"][-1])
        self.assertTrue(all(r["sequence"] >= 5 for r in records))

    def test_intent_and_off_grid_actions_are_not_labels(self):
        _, meta, rows = fixture(self.root)
        rows[5]["intent"] = {LIMIT: 200, POOL: 30}
        rows[6]["action"][LIMIT] = 8
        records, excluded = samples(meta, rows)
        self.assertEqual(next(r for r in records if r["sequence"] == 5)["action"], [0, 0])
        self.assertTrue(excluded["action_outside_grid_or_mask"] >= 1)
        self.assertNotIn(6, [r["sequence"] for r in records])

    def test_grouping_keeps_policies_repetitions_and_seeds_together(self):
        for condition in range(3):
            fixture(self.root / "raw", condition)
            fixture(self.root / "raw", condition, "adaptive", 2)
        first = prepare(self.root / "raw", self.root / "prepared", seed=7)
        second = prepare(self.root / "raw", self.root / "prepared-again", seed=7)
        self.assertEqual(first, second)
        self.assertEqual(first["sampleCounts"], {"train": 14, "validation": 14, "test": 14})
        assigned = {}
        for episode in first["episodes"]:
            assigned.setdefault(episode["groupId"], set()).add(episode["split"])
        self.assertTrue(all(len(splits) == 1 for splits in assigned.values()))
        with self.assertRaises(FileExistsError):
            prepare(self.root / "raw", self.root / "prepared", seed=7)

    def test_held_out_profiles_never_enter_train_or_validation(self):
        groups = {"a": {"profile": {"name": "steady"}}, "b": {"profile": {"name": "steady"}},
                  "c": {"profile": {"name": "spike"}}, "d": {"profile": {"name": "spike"}}}
        assigned = partition_groups(groups, 42, ("spike",))
        self.assertEqual(assigned["c"], "test")
        self.assertEqual(assigned["d"], "test")
        self.assertEqual({assigned["a"], assigned["b"]}, {"train", "validation"})

    def test_dirty_and_small_collections_fail_before_writing_output(self):
        fixture(self.root / "raw", dirty=True)
        with self.assertRaisesRegex(ValueError, "uncommitted source"):
            prepare(self.root / "raw", self.root / "out")
        self.assertFalse((self.root / "out").exists())
        with self.assertRaisesRegex(ValueError, "three distinct"):
            prepare(self.root / "raw", self.root / "out", allow_dirty=True)

    def test_coverage_distinguishes_possible_from_observed_actions(self):
        _, meta, rows = fixture(self.root)
        records, _ = samples(meta, rows)
        for r in records:
            r.update(runId="r", episodeId="e")
        audit = coverage(records)
        self.assertEqual(audit["readiness"], "not_established")
        joint_bin = next(b for name, b in audit["bins"].items() if name.endswith("pool_tick"))
        self.assertEqual(len(joint_bin), 9)
        self.assertEqual(joint_bin["[1,1]"]["samples"], 0)
        self.assertGreater(joint_bin["[1,1]"]["opportunities"], 0)
        self.assertFalse(joint_bin["[0,0]"]["supported"])  # One episode is not independent support.

    def test_matrix_commands_are_argv_and_failure_keeps_completion_state(self):
        matrix = {"matrixVersion": 1, "cases": [{"id": "a", "properties": {"bench.poolSize": 4}},
                                                   {"id": "b", "properties": {"bench.poolSize": 8}}]}
        jobs = commands(matrix, self.root / "space in path")
        self.assertIn(f"-Dbench.out={(self.root / 'space in path' / 'a').resolve()}", jobs[0][1])
        with patch("collect_dataset.subprocess.run", side_effect=[None, subprocess.CalledProcessError(1, "mvn")]):
            with self.assertRaises(subprocess.CalledProcessError):
                collect(matrix, self.root / "collection")
        status = json.loads((self.root / "collection" / "collection.json").read_text())
        self.assertEqual(status["completed"], ["a"])
        self.assertEqual(status["status"], "running")
        with self.assertRaisesRegex(ValueError, "unfinished collection"):
            prepare(self.root / "collection", self.root / "out")


if __name__ == "__main__":
    unittest.main()
