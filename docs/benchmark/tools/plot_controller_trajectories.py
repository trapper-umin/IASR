#!/usr/bin/env python3
"""Plot observed server windows and applied resources for one paired technical scenario."""
import argparse
import json
import os
from tempfile import TemporaryDirectory
from pathlib import Path
from validate_episodes import read_json, timestamp, require
from policy_data import LIMIT, POOL


def render(root, scenario, destination):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.lines import Line2D
    root, destination = Path(root), Path(destination)
    plan = read_json(root / "execution.json")["plan"]
    arms = list(plan["arms"])
    require(len(arms) == 2, "plot requires a paired comparison")
    figure, axes = plt.subplots(4, 2, figsize=(11.5, 8.7), sharex=True, sharey="row")
    colors = {"model": "#176a98", "fallback": "#c46716"}
    duration = None
    maxima = [0, 0, 0, 0]
    for column, arm in enumerate(arms):
        paths = [p for p in (root / arm).rglob("metadata.json") if read_json(p)["scenario"] == scenario]
        require(len(paths) == 1, "select a scenario with exactly one episode per arm")
        metadata = read_json(paths[0])
        start, end = (timestamp(metadata["timing"][key]) for key in ("measurementStartedAt", "measurementScheduledEndAt"))
        duration = (end - start).total_seconds()
        rows = [json.loads(line) for line in paths[0].with_name("dataset.jsonl").read_text().splitlines()]
        measured = [r for r in rows if r["stateWindowStartedAt"] is not None
                    and timestamp(r["stateWindowStartedAt"]) >= start and timestamp(r["timestamp"]) <= end]
        times = [(timestamp(r["timestamp"]) - start).total_seconds() for r in measured]
        for index, key in enumerate(("latency_p95_ms", "goodput_rps")):
            values = [r["state"].get(key) for r in measured]
            maxima[index] = max(maxima[index], max((v for v in values if v is not None), default=0))
            axes[index, column].plot(times, values, color="#333333", lw=1.5)
        maxima[0] = max(maxima[0], metadata["configuration"]["sloMs"])
        axes[0, column].axhline(metadata["configuration"]["sloMs"], color="#9c3549", ls="--", lw=1)
        for index, actuator in enumerate((LIMIT, POOL), start=2):
            relevant = [r for r in rows if start <= timestamp(r["timestamp"]) <= end]
            at = [0] + [(timestamp(r["timestamp"]) - start).total_seconds() for r in relevant]
            values = [metadata["measurementInitialResources"][actuator]] + [r["action"][actuator] for r in relevant]
            at.append(duration); values.append(values[-1])
            axes[index, column].step(at, values, where="post", color="#555555", lw=1.3)
            for row in relevant:
                if row["action"][actuator] == row["resourcesBefore"][actuator]:
                    continue
                modeled = row["reasons"][actuator].startswith("learned_policy")
                axes[index, column].scatter((timestamp(row["timestamp"]) - start).total_seconds(), row["action"][actuator],
                                            color=colors["model" if modeled else "fallback"], marker="o" if modeled else "^", s=26, zorder=3)
            maxima[index] = max(maxima[index], max(values))
        axes[0, column].set_title(f"{arm}: {plan['arms'][arm]['candidate']}", fontsize=11, pad=9)
        axes[3, column].set_xlabel("Measurement time (s)")
    for index, label in enumerate(("Server window p95 (ms)", "Server goodput (rps)", "Concurrency limit", "Maximum pool size")):
        axes[index, 0].set_ylabel(label)
        for ax in axes[index]:
            ax.set_xlim(0, duration)
            ax.set_ylim(0, max(1, maxima[index] * 1.08))
            ax.grid(alpha=0.2)
            ax.spines[["top", "right"]].set_visible(False)
    legend = [Line2D([], [], color="#9c3549", ls="--", label="SLO"),
              Line2D([], [], color=colors["model"], marker="o", lw=0, label="Model action"),
              Line2D([], [], color=colors["fallback"], marker="^", lw=0, label="Baseline fallback action")]
    figure.suptitle(f"Joint-action Q representation: {scenario}", fontsize=14, y=0.98)
    figure.legend(handles=legend, loc="upper center", bbox_to_anchor=(0.5, 0.945), ncol=3, frameon=False)
    figure.text(0.5, 0.025, "One technical run per controller; known development conditions. No statistical performance conclusion.\n"
                "Metrics include only complete measurement windows; resource levels are targets applied after each tick.", ha="center", fontsize=8)
    figure.subplots_adjust(left=0.09, right=0.97, top=0.86, bottom=0.10, hspace=0.22, wspace=0.12)
    destination.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(destination, dpi=160)
    figure.savefig(destination.with_suffix(".svg"))
    plt.close(figure)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    with TemporaryDirectory(prefix="iasr-plot-cache-") as cache:
        os.environ.setdefault("MPLCONFIGDIR", cache)
        os.environ.setdefault("XDG_CACHE_HOME", cache)
        render(args.raw, args.scenario, args.out)
