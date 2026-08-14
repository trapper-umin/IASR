#!/usr/bin/env python3
"""
Turn a benchmark run log into a table with confidence intervals.

Why this exists separately from the harness: the harness picks one
representative run per combination so its reported figures stay mutually
consistent, which is right for a single number but throws away the spread. A
paper needs the spread, and it needs an interval rather than a min–max range —
a range widens with every extra repetition, so quoting one rewards running
fewer.

Intervals are bootstrap percentile intervals on the median. Bootstrap rather
than a t-interval because seven repetitions is far too few to assume normality,
and the median rather than the mean because a single slow run should not move
the summary.
"""
import argparse
import random
import re
import statistics
from collections import defaultdict

RUN = re.compile(
    r'\s+\[\d+/\d+\] goodput=([\d,.]+) rps\s+p95=([\d,.]+) ms\s+'
    r'p99=([\d,.]+) ms\s+429=([\d,.]+)%\s+hard-fail=([\d,.]+)%')
HEAD = re.compile(r'=== (\S+) / (\S+) ===')
METRICS = ['goodput', 'p95', 'p99', 'shed', 'hard_fail']


def num(s):
    return float(s.replace(',', '.'))


def parse(path):
    data, current = defaultdict(list), None
    with open(path, errors='ignore') as fh:
        for line in fh:
            head = HEAD.match(line.strip())
            if head:
                current = (head.group(1), head.group(2))
                data.setdefault(current, [])
                continue
            run = RUN.match(line)
            if run and current:
                data[current].append([num(g) for g in run.groups()])
    return data


def bootstrap_ci(values, confidence=0.95, resamples=10000, seed=20260814):
    """Percentile interval for the median. Deterministic seed so the numbers in
    the paper can be regenerated exactly."""
    if len(values) < 2:
        return (values[0], values[0]) if values else (0.0, 0.0)
    rng = random.Random(seed)
    n = len(values)
    medians = sorted(
        statistics.median(rng.choices(values, k=n)) for _ in range(resamples))
    lo = medians[int((1 - confidence) / 2 * resamples)]
    hi = medians[int((1 + confidence) / 2 * resamples) - 1]
    return lo, hi


def separated(a, b):
    """True when the two intervals do not overlap — the only case where a
    difference should be reported as one."""
    return a[1] < b[0] or b[1] < a[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('--baseline', default='gradient',
                    help='mode each other is compared against')
    ap.add_argument('--csv', help='write the table here as well')
    args = ap.parse_args()

    data = parse(args.log)
    scenarios, modes = [], []
    for sc, mode in data:
        if sc not in scenarios:
            scenarios.append(sc)
        if mode not in modes:
            modes.append(mode)

    rows = []
    for sc in scenarios:
        print(f"\n=== {sc} ===")
        print(f"{'mode':12}{'n':>3}{'goodput (95% CI)':>28}{'p95 (95% CI)':>26}{'shed %':>8}")
        base = data.get((sc, args.baseline), [])
        base_gp = [r[0] for r in base]
        base_ci = bootstrap_ci(base_gp) if base_gp else None
        for mode in modes:
            runs = data.get((sc, mode), [])
            if not runs:
                continue
            cols = {m: [r[i] for r in runs] for i, m in enumerate(METRICS)}
            gp_ci = bootstrap_ci(cols['goodput'])
            p95_ci = bootstrap_ci(cols['p95'])
            mark = ''
            if base_ci and mode != args.baseline:
                mark = ' *' if separated(gp_ci, base_ci) else '  '
            print(f"{mode:12}{len(runs):3d}"
                  f"{statistics.median(cols['goodput']):9.1f} "
                  f"[{gp_ci[0]:6.1f}–{gp_ci[1]:6.1f}]{mark:>2}"
                  f"{statistics.median(cols['p95']):9.0f} "
                  f"[{p95_ci[0]:5.0f}–{p95_ci[1]:5.0f}]"
                  f"{statistics.median(cols['shed']):8.1f}")
            rows.append([sc, mode, len(runs),
                         statistics.median(cols['goodput']), gp_ci[0], gp_ci[1],
                         statistics.median(cols['p95']), p95_ci[0], p95_ci[1],
                         statistics.median(cols['p99']),
                         statistics.median(cols['shed']),
                         statistics.median(cols['hard_fail'])])
    print(f"\n* goodput interval separated from '{args.baseline}' — a difference "
          f"that can be reported as one")

    if args.csv:
        with open(args.csv, 'w') as fh:
            fh.write('scenario,mode,n,goodput_median,goodput_ci_lo,goodput_ci_hi,'
                     'p95_median,p95_ci_lo,p95_ci_hi,p99_median,shed_pct,hard_failure_pct\n')
            for r in rows:
                fh.write(','.join(f'{x:.3f}' if isinstance(x, float) else str(x)
                                  for x in r) + '\n')
        print(f"CSV: {args.csv}")


if __name__ == '__main__':
    main()
