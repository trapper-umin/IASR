import json
import shutil
import tempfile
import unittest
from pathlib import Path
from replay_jvm_policy import replay


class JvmReplayTest(unittest.TestCase):
    ROOT = Path(__file__).resolve().parents[1]
    RAW = ROOT / "raw/jvm-policy-smoke-2026-11-08"
    MODEL = ROOT / "models/fqi-prototype-2026-11-08/model.json"

    def test_replays_real_closed_loop_decisions_and_support_fallback(self):
        report = replay(self.RAW, self.MODEL)
        self.assertEqual(report["integrity"]["records"], 179)
        self.assertEqual(sum(r["counts"].get("matched_model_decisions", 0) for r in report["replay"]), 80)
        self.assertEqual(sum(r["counts"].get("matched_support_fallbacks", 0) for r in report["replay"]), 1)

    def test_rejects_altered_jvm_proposal(self):
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary) / "raw"
            shutil.copytree(self.RAW, raw)
            path = next(raw.rglob("steady/learned/repeat-0001/dataset.jsonl"))
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            row = next(r for r in rows if r["reasons"]["concurrency_limit"].startswith("learned_policy"))
            row["intent"]["concurrency_limit"] += 2
            path.write_text("".join(json.dumps(r) + "\n" for r in rows))
            with self.assertRaisesRegex(ValueError, "decision differs"):
                replay(raw, self.MODEL)
