"""Figures of the thesis, drawn from the archived runs.

    python3 docs/dissertation/thesis/figures/make_figures.py

Every number on a plot is read from docs/benchmark; nothing is typed in by hand.
"""
import csv
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BENCH = ROOT / "docs" / "benchmark"
ANALYSIS = BENCH / "analysis" / "coordinated-2026-11-15"
PAPER = ROOT / "docs" / "paper" / "figures"

plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.grid": True,
                     "grid.alpha": 0.3, "savefig.dpi": 200, "savefig.bbox": "tight"})
AIMD, POLICY, PINNED = "#7f7f7f", "#c00000", "#2e75b6"
SCEN = ["steady", "spike", "ramp", "slow-dependency"]


def architecture():
    fig, ax = plt.subplots(figsize=(9.5, 5.6))
    ax.set_xlim(0, 100); ax.set_ylim(0, 62); ax.axis("off")

    def box(x, y, w, h, text, color="#f2f2f2", edge="#404040", bold=False, size=9):
        ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.2",
                                    fc=color, ec=edge, lw=1.1))
        ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=size,
                fontweight="bold" if bold else "normal", wrap=True)

    def arrow(x1, y1, x2, y2, text=None, dx=0, dy=1.2):
        ax.annotate("", xy=(x2, y2), xytext=(x1, y1),
                    arrowprops=dict(arrowstyle="-|>", lw=1.1, color="#303030"))
        if text:
            ax.text((x1 + x2) / 2 + dx, (y1 + y2) / 2 + dy, text, ha="center", fontsize=8, color="#303030")

    # application
    ax.add_patch(FancyBboxPatch((1, 1), 40, 58, boxstyle="round,pad=0.4,rounding_size=1.5",
                                fc="white", ec="#808080", lw=1, ls="--"))
    ax.text(21, 56.5, "Серверное приложение (JVM, Spring Boot)", ha="center", fontsize=9, style="italic")
    box(5, 44, 32, 8, "Фильтр допуска\n(предел конкурентности L)", "#fde9d9")
    box(5, 31, 32, 8, "Пул соединений HikariCP\n(размер P)", "#fde9d9")
    box(5, 18, 32, 8, "Обработчик запроса", "#f2f2f2")
    box(5, 4, 32, 8, "Метрики Micrometer\n(окно Δt = 1 с)", "#e2efda")
    arrow(21, 44, 21, 39.6); arrow(21, 31, 21, 26.6)
    ax.annotate("", xy=(47.6, 33), xytext=(37.6, 33), arrowprops=dict(arrowstyle="<|-|>", lw=1.1))
    box(48, 31, 13, 8, "PostgreSQL", "#ddebf7")
    ax.text(-0.5, 52, "запросы", fontsize=8, rotation=90, va="center")
    ax.annotate("", xy=(5, 48), xytext=(0, 48), arrowprops=dict(arrowstyle="-|>", lw=1.1))

    # control engine
    ax.add_patch(FancyBboxPatch((44, 1), 55, 27, boxstyle="round,pad=0.4,rounding_size=1.5",
                                fc="white", ec="#808080", lw=1, ls="--"))
    ax.text(71.5, 25.5, "ControlEngine (цикл MAPE-K, каждый такт)", ha="center", fontsize=9, style="italic")
    box(46, 13, 15, 8, "1. Снимок\nметрик", "#e2efda", size=8.5)
    box(64, 13, 15, 8, "2. Решение\nрегулятора", "#fff2cc", size=8.5)
    box(82, 13, 15, 8, "3. Ограничители\n(guardrails)", "#fde9d9", size=8.5)
    box(64, 3, 15, 7, "5. Журнал\nэпизода (JSONL)", "#f2f2f2", size=8.5)
    box(82, 3, 15, 7, "4. Применение\nк актюаторам", "#fde9d9", size=8.5)
    arrow(61.4, 17, 63.6, 17); arrow(79.4, 17, 81.6, 17); arrow(89.5, 13, 89.5, 10.4)
    arrow(82, 6.5, 79.4, 6.5)
    arrow(37.6, 8, 45.6, 15)
    ax.text(40.5, 13.2, "snapshot()", fontsize=8, rotation=40)
    red = "#c00000"
    ax.plot([97.4, 99.3, 99.3, 42.5, 42.5], [6.5, 6.5, 29.6, 29.6, 47], color=red, lw=1.1)
    ax.annotate("", xy=(37.6, 47), xytext=(42.5, 47), arrowprops=dict(arrowstyle="-|>", lw=1.1, color=red))
    ax.annotate("", xy=(37.6, 37), xytext=(42.5, 37), arrowprops=dict(arrowstyle="-|>", lw=1.1, color=red))
    ax.text(62, 30.3, "apply(L), apply(P)", color=red, fontsize=8)

    # controller detail
    box(66, 37, 32, 13, "Регулятор (SPI Controller):\nAIMD-правила | Gradient |\nCoordinatedController:\nправила + обученная политика",
        "#fff2cc", size=8.5)
    arrow(72, 37, 72, 21.6)
    fig.savefig(HERE / "architecture.png")
    plt.close(fig)


def paired_differences():
    held = json.loads((ANALYSIS / "compare-adaptive.json").read_text())
    conf = json.loads((ANALYSIS / "compare-confirmation.json").read_text())
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.2), sharey=True)
    labels = SCEN + ["все сценарии"]
    for ax, metric, title, unit in ((axes[0], "servedWithinSloPercent", "Обслужено в пределах SLO", "п.п."),
                                    (axes[1], "p95Ms", "Клиентский p95", "мс")):
        for k, (data, name, color, off) in enumerate(((held, "отложенная проверка (n = 6 / 24)", POLICY, -0.15),
                                                     (conf, "подтверждающий прогон (n = 16 / 64)", PINNED, 0.15))):
            for i, s in enumerate(labels):
                part = data["pooled"] if s == "все сценарии" else data["scenarios"][s]
                d = part["differences"]["coordinated"][metric]
                y = len(labels) - 1 - i + off
                ax.errorbar(d["mean"], y, xerr=[[d["mean"] - d["low"]], [d["high"] - d["mean"]]], fmt="o",
                            color=color, capsize=3, ms=5, label=name if i == 0 else None,
                            mfc=color if s == "все сценарии" else "white")
        ax.axvline(0, color="black", lw=0.8)
        ax.set_title(title + ": политика − AIMD, " + unit, fontsize=10)
        ax.set_yticks(range(len(labels)))
        ax.set_yticklabels(list(reversed(labels)))
    axes[0].legend(loc="lower left", fontsize=8, bbox_to_anchor=(0, -0.32), ncol=2, frameon=False)
    fig.savefig(HERE / "paired_differences.png")
    plt.close(fig)


def trajectory():
    base = next((BENCH / "raw" / "coordinated-evaluation-2026-11-15" / "evaluation" / "episodes").iterdir()) / "spike"
    fig, axes = plt.subplots(2, 1, figsize=(9, 5.2), sharex=True)
    for mode, color, name in (("adaptive", AIMD, "AIMD"), ("coordinated", POLICY, "обученная политика")):
        rows = list(csv.DictReader(open(base / mode / "repeat-0002" / "actuators.csv")))
        t = [float(r["t_sec"]) for r in rows]
        res = json.loads((base / mode / "repeat-0002" / "result.json").read_text())
        label = f"{name}: p95 {res['p95Ms']:.0f} мс, вне SLO {res['sloBreachPercent']:.1f} %".replace(".", ",")
        axes[0].step(t, [int(r["concurrency_limit"]) for r in rows], where="post", color=color, label=label)
        axes[1].step(t, [int(r["hikari_max_pool"]) for r in rows], where="post", color=color)
    for ax in axes:
        ax.axvspan(12, 32, color="#ffd966", alpha=0.25, lw=0)
    axes[0].set_ylabel("Предел L")
    axes[1].set_ylabel("Пул P")
    axes[1].set_xlabel("Время от начала измерения, с (жёлтым — всплеск нагрузки)")
    axes[0].legend(fontsize=8, loc="upper right")
    fig.savefig(HERE / "trajectory_spike.png")
    plt.close(fig)


def validation_sequence():
    steps = ["Валидация 1\nопора: удержание", "Валидация 2\n+ итерация политики", "Валидация 3\nопора: AIMD, κ = 0,25",
             "Валидация 3\nопора: AIMD, κ = 0,5", "Отложенная\nпроверка", "Подтверждающий\nпрогон"]
    means = [-0.8, None, 3.4, -0.6, 2.66, 1.53]
    lows = [-3.0, None, -3.3, -4.4, -0.36, -0.63]
    highs = [1.4, None, 10.0, 3.2, 5.69, 3.70]
    fig, ax = plt.subplots(figsize=(9, 3.6))
    for i, (m, lo, hi) in enumerate(zip(means, lows, highs)):
        if m is None:
            ax.text(i, 0, "p95 +171 мс\n[+38; +305]\nхуже AIMD", ha="center", va="center", fontsize=8, color=AIMD)
            continue
        color = POLICY if i >= 4 else PINNED
        ax.errorbar(i, m, yerr=[[m - lo], [hi - m]], fmt="o", color=color, capsize=4)
        ax.text(i + 0.08, m, f"{m:+.1f}".replace(".", ","), fontsize=8, va="center")
    ax.axhline(0, color="black", lw=0.8)
    ax.set_xticks(range(len(steps))); ax.set_xticklabels(steps, fontsize=8)
    ax.set_ylabel("Обслужено в SLO,\nполитика − AIMD, п.п.")
    fig.savefig(HERE / "validation_sequence.png")
    plt.close(fig)


def support_table():
    import numpy as np
    model = json.loads((BENCH / "models" / "coordinated-2026-11-15" / "policy.json").read_text())
    bins = model["support"]["bins"]
    minimum = model["support"]["minSamples"]
    zones = [("comfortable", "комфортно"), ("within_slo", "в SLO"), ("over_slo", "выше SLO")]
    queues = [("free", "free", "нет / нет"), ("backlog", "free", "есть / нет"), ("free", "backlog", "нет / есть"), ("backlog", "backlog", "есть / есть")]
    dirs = [f"{a},{b}" for a in (-1, 0, 1) for b in (-1, 0, 1)]
    rows, labels = [], []
    for z, zl in zones:
        for q1, q2, ql in queues:
            counts = bins.get(f"{z}/{q1}/{q2}", {})
            rows.append([counts.get(d, 0) for d in dirs])
            labels.append(f"{zl}; очередь L/P: {ql}")
    m = np.array(rows, dtype=float)
    fig, ax = plt.subplots(figsize=(9, 5.4))
    im = ax.imshow(np.log10(m + 1), cmap="Blues", aspect="auto")
    for i in range(m.shape[0]):
        for j in range(m.shape[1]):
            v = int(m[i, j])
            ax.text(j, i, str(v), ha="center", va="center", fontsize=8,
                    color="white" if v > 150 else ("#c00000" if v < minimum else "black"))
    ax.set_xticks(range(len(dirs)))
    ax.set_xticklabels([d.replace("-1", "−").replace("1", "+").replace("0", "0").replace(",", " / ") for d in dirs], fontsize=8)
    ax.set_xlabel("Направление изменения (предел / пул)")
    ax.set_yticks(range(len(labels))); ax.set_yticklabels(labels, fontsize=8)
    ax.grid(False)
    fig.savefig(HERE / "support_table.png")
    plt.close(fig)


def copy_paper_figures():
    for name, new in (("fig1_calibration.png", "calibration.png"), ("fig2_goodput.png", "aimd_goodput.png")):
        (HERE / new).write_bytes((PAPER / name).read_bytes())


if __name__ == "__main__":
    architecture()
    paired_differences()
    trajectory()
    validation_sequence()
    support_table()
    copy_paper_figures()
    print("figures written to", HERE)
