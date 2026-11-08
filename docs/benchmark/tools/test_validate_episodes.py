import copy
import json
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timedelta
from validate_episodes import validate_episode, validate_collection


class EpisodeValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.metadata = {"metadataVersion": 1, "datasetSchemaVersion": 3, "status": "complete",
                         "runId": "run-1", "episodeId": "spike/adaptive/1", "mode": "adaptive", "repetition": 1,
                         "orderSeed": 42, "source": {"commit": "abc", "dirty": False}, "datasetRecords": 5,
                         "timing": {"measurementStartedAt": "2026-11-08T10:00:01Z",
                                    "measurementScheduledEndAt": "2026-11-08T10:00:04Z",
                                    "captureEndedAt": "2026-11-08T10:00:05Z"}}
        self.rows = []
        for step in range(5):
            at = lambda n: f"2026-11-08T10:00:0{n}Z"
            self.rows.append({"schemaVersion": 3, "sequence": step, "windowSec": 1,
                              "timestamp": at(step), "stateWindowStartedAt": at(step - 1) if step else None,
                              "outcomeTimestamp": at(step + 1) if step < 4 else None,
                              "state": {"goodput_rps": step}, "resourcesBefore": {"limit": 10},
                              "action": {"limit": 10}, "intent": {}, "cooldownRemaining": {"limit": 0},
                              "outcome": {"goodput_rps": step + 1} if step < 4 else None})

    def write(self, directory=None):
        directory = directory or self.root
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "metadata.json").write_text(json.dumps(self.metadata))
        (directory / "dataset.jsonl").write_text("\n".join(json.dumps(r) for r in self.rows) + "\n")
        (directory / "result.json").write_text("{}")
        (directory / "actuators.csv").write_text("t_sec,concurrency_limit,hikari_max_pool,inflight\n")
        return directory

    def test_preserves_terminal_but_excludes_bootstrap_and_warmup_boundaries(self):
        result = validate_episode(self.write())
        self.assertEqual(result["records"], 5)
        self.assertEqual(result["measurementTransitions"], 2)

    def test_rejects_missing_terminal_even_when_count_matches(self):
        self.rows.pop()
        self.metadata["datasetRecords"] -= 1
        with self.assertRaisesRegex(ValueError, "terminal record"):
            validate_episode(self.write())

    def test_rejects_legacy_metrics_and_broken_transition_links(self):
        for mutation in (lambda rows: rows[1].update(schemaVersion=2),
                         lambda rows: rows[1].update(sequence=7),
                         lambda rows: rows[1].update(state={"goodput_rps": 999}),
                         lambda rows: rows[1].update(resourcesBefore={"limit": 20})):
            original = copy.deepcopy(self.rows)
            mutation(self.rows)
            with self.assertRaises(ValueError):
                validate_episode(self.write())
            self.rows = original

    def test_rejects_incomplete_episode_in_collection(self):
        self.write(self.root / "repeat-0001")
        (self.root / "repeat-0002").mkdir()
        with self.assertRaisesRegex(ValueError, "incomplete episode"):
            validate_collection(self.root)

    def test_off_mode_has_no_control_transitions(self):
        self.metadata.update(mode="off", datasetRecords=0)
        self.rows = []
        self.write()
        (self.root / "dataset.jsonl").write_text("")
        self.assertEqual(validate_episode(self.root)["measurementTransitions"], 0)

    def test_rejects_nonfinite_metrics(self):
        self.rows[0]["state"]["goodput_rps"] = float("nan")
        with self.assertRaises(ValueError):
            validate_episode(self.write())

    def test_irregular_windows_are_reported_and_excluded(self):
        for row in self.rows:
            for key in ("timestamp", "stateWindowStartedAt", "outcomeTimestamp"):
                if row[key] is not None:
                    at = datetime.fromisoformat(row[key].replace("Z", "+00:00"))
                    if at.second >= 2:
                        row[key] = (at + timedelta(seconds=10)).isoformat()
        self.metadata["timing"].update(measurementScheduledEndAt="2026-11-08T10:00:20Z",
                                      captureEndedAt="2026-11-08T10:00:21Z")
        result = validate_episode(self.write())
        self.assertEqual(result["irregularTransitions"], 2)
        self.assertEqual(result["measurementTransitions"], 1)

    def test_duplicate_episode_identities_are_rejected(self):
        self.write(self.root / "repeat-0001")
        self.write(self.root / "repeat-0002")
        with self.assertRaisesRegex(ValueError, "duplicate episode identity"):
            validate_collection(self.root)

    def test_unsafe_intent_can_be_audited_without_treating_it_as_applied(self):
        self.rows[1]["intent"] = {"limit": -10}
        result = validate_episode(self.write())
        self.assertEqual(result["measurementTransitions"], 2)


if __name__ == "__main__":
    unittest.main()
