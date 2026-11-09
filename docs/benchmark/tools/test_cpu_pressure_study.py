import unittest

from cpu_pressure_study import schedule


class CpuPressureStudyTest(unittest.TestCase):
    def test_balances_positions_and_direct_predecessors(self):
        a, b, c, d = ("adaptive", 0), ("learned", 0), ("adaptive", 8), ("learned", 8)
        plan = {"purpose": "held_out_postgresql_cpu_pressure", "pressureWorkers": 8,
                "blocks": [(a, b, d, c), (b, c, a, d), (c, d, b, a), (d, a, c, b)]}
        trials = list(schedule(plan))
        self.assertEqual(len(trials), 16)
        self.assertEqual(trials[2], {"block": 1, "position": 3, "mode": "learned", "cpuWorkers": 8})
        plan["blocks"] = [(a, b, c, d), (b, c, d, a), (c, d, a, b), (d, a, b, c)]
        with self.assertRaisesRegex(ValueError, "carryover"):
            list(schedule(plan))
