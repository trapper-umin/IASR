#!/usr/bin/env python3
"""Paired comparison of control modes over the repetitions of a benchmark run."""
import argparse
import collections
import json
import math
from pathlib import Path

from scipy import stats

METRICS = ["servedWithinSloPercent", "goodputRps", "p95Ms", "p99Ms", "sloBreachPercent", "rejectionRatePercent",
           "failureRatePercent"]


def served_within_slo_percent(result):
    """Client-side share of dispatched requests answered successfully within the objective."""
    if not result["dispatched"]:
        return math.nan
    return 100.0 * result["succeeded"] * (1.0 - result["sloBreachPercent"] / 100.0) / result["dispatched"]


def read(roots):
    """{(scenario, repetition): {mode: metrics}} for every finished episode below the roots."""
    if isinstance(roots, (str, Path)):
        roots = [roots]
    blocks = collections.defaultdict(dict)
    for path in sorted(p for root in roots for p in Path(root).rglob("result.json")):
        result = json.loads(path.read_text())
        metadata = json.loads((path.parent / "metadata.json").read_text())
        result["servedWithinSloPercent"] = served_within_slo_percent(result)
        result["inference"] = metadata.get("inference")
        blocks[(result["scenario"], metadata["repetition"])][result["mode"]] = result
    return blocks


def interval(values):
    """Mean and two-sided 95% Student interval of paired differences."""
    n = len(values)
    mean = sum(values) / n
    if n < 2:
        return mean, math.nan, math.nan
    sd = math.sqrt(sum((v - mean) ** 2 for v in values) / (n - 1))
    half = stats.t.ppf(0.975, n - 1) * sd / math.sqrt(n)
    return mean, mean - half, mean + half


def compare(roots, reference, modes=None):
    blocks = read(roots)
    scenarios = sorted({scenario for scenario, _ in blocks})
    all_modes = sorted({mode for block in blocks.values() for mode in block})
    modes = modes or [m for m in all_modes if m != reference]
    report = {"reference": reference, "scenarios": {}, "pooled": {}}
    for scope in scenarios + ["*"]:
        chosen = [block for (scenario, _), block in sorted(blocks.items()) if scope in ("*", scenario)]
        entry = {"blocks": len(chosen), "means": {}, "differences": {}}
        for mode in [reference] + modes:
            rows = [block[mode] for block in chosen if mode in block]
            if rows:
                entry["means"][mode] = {m: sum(r[m] for r in rows) / len(rows) for m in METRICS}
        for mode in modes:
            pairs = [(block[mode], block[reference]) for block in chosen if mode in block and reference in block]
            if not pairs:
                continue
            entry["differences"][mode] = {"pairs": len(pairs)}
            for metric in METRICS:
                mean, low, high = interval([a[metric] - b[metric] for a, b in pairs])
                entry["differences"][mode][metric] = {"mean": mean, "low": low, "high": high}
        if scope == "*":
            report["pooled"] = entry
        else:
            report["scenarios"][scope] = entry
    return report


def table(report):
    lines = []
    for scope, entry in list(report["scenarios"].items()) + [("all scenarios", report["pooled"])]:
        lines.append(f"## {scope} ({entry['blocks']} blocks)")
        lines.append("| mode | served in SLO, % | goodput, rps | p95, ms | p99, ms | over SLO, % | 429, % |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|")
        for mode, m in entry["means"].items():
            lines.append(f"| {mode} | {m['servedWithinSloPercent']:.2f} | {m['goodputRps']:.2f} | {m['p95Ms']:.1f} | "
                         f"{m['p99Ms']:.1f} | {m['sloBreachPercent']:.2f} | {m['rejectionRatePercent']:.2f} |")
        for mode, d in entry["differences"].items():
            parts = [f"{metric} {d[metric]['mean']:+.2f} [{d[metric]['low']:+.2f} … {d[metric]['high']:+.2f}]"
                     for metric in ("servedWithinSloPercent", "goodputRps", "p95Ms")]
            lines.append(f"- {mode} − {report['reference']} (n={d['pairs']}): " + "; ".join(parts))
        lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("roots", type=Path, nargs="+")
    parser.add_argument("--reference", default="adaptive")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    report = compare(args.roots, args.reference)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n")
    print(table(report))


if __name__ == "__main__":
    main()
