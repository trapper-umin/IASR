import unittest
from datetime import datetime, timedelta, timezone

from benchmark_cpu import cpu_delta, process_times, summarize_cpu


class BenchmarkCpuTest(unittest.TestCase):
    def test_counts_only_descendants_including_new_workers(self):
        output = "20 10 0:00.10\n21 20 1:00.20\n22 21 1:02:03.04\n30 10 0:00.30\n31 30 0:00.50\n40 10 1:00.00\n"
        trees = process_times(output, {"jvm": 20, "postgres": 30})
        self.assertEqual(trees["jvm"], {20: 0.1, 21: 60.2, 22: 3723.04})
        self.assertEqual(trees["postgres"], {30: 0.3, 31: 0.5})
        self.assertAlmostEqual(cpu_delta({30: 0.1, 32: 2.0}, trees["postgres"]), 0.7)

    def test_weights_by_sampled_time_and_skips_boundary_or_gap(self):
        start = datetime(2026, 11, 9, tzinfo=timezone.utc)

        def sample(left, right, cpu):
            return (start + timedelta(seconds=left), start + timedelta(seconds=right),
                    {"elapsedSec": right - left, "jvm": {"cpuSec": cpu, "started": 0, "exited": 0},
                     "postgres": {"cpuSec": cpu * 2, "started": 1, "exited": 0}})

        rows = [sample(-1, 0.1, 100), sample(0.1, 1.1, 1), sample(1.1, 2.3, 2),
                sample(2.3, 5.1, 100), sample(5.1, 6.1, 100)]
        result = summarize_cpu(rows, start, start + timedelta(seconds=6), 1)
        self.assertEqual(result["samples"], 2)
        self.assertEqual(result["postgres"]["started"], 2)
        self.assertAlmostEqual(result["fractionCovered"], 2.2 / 6)
        self.assertAlmostEqual(result["jvm"]["meanCores"], 3 / 2.2)
        self.assertAlmostEqual(result["postgres"]["meanCores"], 6 / 2.2)
