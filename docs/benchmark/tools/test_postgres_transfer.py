from datetime import datetime, timedelta, timezone
import unittest

from audit_postgres_transfer import paired_interval, recovery


class PostgresTransferTest(unittest.TestCase):
    START = datetime(2026, 11, 9, tzinfo=timezone.utc)
    CRITERION = {"disturbanceEndSec": 35, "offeredRps": 75, "minGoodputFraction": 0.9, "consecutiveWindows": 3}

    def window(self, sequence, start, goodput=68, p95=145, length=1):
        return {"sequence": sequence, "windowSec": 1,
                "stateWindowStartedAt": (self.START + timedelta(seconds=start)).isoformat(),
                "timestamp": (self.START + timedelta(seconds=start + length)).isoformat(),
                "state": {"goodput_rps": goodput, "latency_p95_ms": p95}}

    def test_recovery_excludes_crossing_window_and_restarts_after_failure(self):
        rows = [self.window(1, 34.5), self.window(2, 35.5), self.window(3, 36.5, goodput=67.4),
                self.window(4, 37.5), self.window(5, 38.5), self.window(6, 39.5)]
        result = recovery(rows, self.START, self.START + timedelta(seconds=45), self.CRITERION, 150)
        self.assertEqual(result, {"seconds": 5.5, "censored": False})

    def test_gap_and_missing_latency_do_not_count_as_recovery(self):
        rows = [self.window(1, 35), self.window(2, 36, length=1.5), self.window(3, 37.5),
                self.window(4, 38.5, p95=None), self.window(5, 39.5), self.window(6, 40.5)]
        result = recovery(rows, self.START, self.START + timedelta(seconds=45), self.CRITERION, 150)
        self.assertEqual(result, {"seconds": None, "censored": True, "observedUntilSeconds": 6.5})

    def test_intervals_keep_the_pairing_and_do_not_drop_undefined_pairs(self):
        result = paired_interval([1, 2, 3, 4])
        self.assertEqual(result["mean"], 2.5)
        self.assertAlmostEqual(result["ci95"][0], 0.4457397, places=6)
        self.assertAlmostEqual(result["ci95"][1], 4.5542603, places=6)
        self.assertIsNone(paired_interval([1, None, 3, 4])["ci95"])
