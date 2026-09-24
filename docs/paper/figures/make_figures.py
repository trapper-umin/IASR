"""Build every figure of both articles from the committed run data.

Run from the repository root:  python3 docs/paper/figures/make_figures.py
Nothing here is drawn by hand, so a changed run regenerates the figures.
"""
import csv
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

OUT = 'docs/paper/figures/'
plt.rcParams.update({'font.size': 9, 'figure.dpi': 200,
                     'axes.grid': True, 'grid.alpha': .3})

CI = {}
for r in csv.DictReader(open('docs/benchmark/raw/postgres/confidence-intervals.csv')):
    CI[(r['scenario'], r['mode'])] = r
G  = lambda s, m: float(CI[(s, m)]['goodput_median'])
LO = lambda s, m: float(CI[(s, m)]['goodput_ci_lo'])
HI = lambda s, m: float(CI[(s, m)]['goodput_ci_hi'])
P95 = lambda s, m: float(CI[(s, m)]['p95_median'])
SH = lambda s, m: float(CI[(s, m)]['shed_pct'])
SCEN = ['spike', 'ramp', 'slow-dependency']


def err(modes_scen):
    v = [G(s, m) for s, m in modes_scen]
    return v, [[v[j] - LO(*modes_scen[j]) for j in range(len(v))],
               [HI(*modes_scen[j]) - v[j] for j in range(len(v))]]


# 1. Calibration
c, t = [], []
for r in csv.DictReader(open('docs/benchmark/raw/calibration.csv')):
    c.append(int(r['concurrency'])); t.append(float(r['throughput_rps']))
fig, ax = plt.subplots(figsize=(5.4, 3.1))
ax.plot(c, t, 'o-', color='#1f4e79', lw=1.6, ms=4)
k = int(np.argmax(t))
ax.axvline(c[k], color='#c00000', ls='--', lw=1.2)
ax.text(c[k] * 1.15, 120, f'максимум: {c[k]} обращений\n(= числу ядер), {t[k]:.0f} rps',
        color='#c00000', fontsize=8.5)
ax.set_xscale('log', base=2); ax.set_xticks(c); ax.set_xticklabels(c)
ax.set_xlabel('Число одновременных обращений к СУБД')
ax.set_ylabel('Пропускная способность, rps')
fig.tight_layout(); fig.savefig(OUT + 'fig1_calibration.png'); plt.close(fig)

# 2. Goodput by mode, legend above the axes so it covers no bars
modes = [('off', 'без ограничения', '#bdbdbd'), ('fixed', 'статический предел', '#7f7f7f'),
         ('gradient', 'Gradient', '#2e75b6'), ('bottleneck', 'идентификация узкого места', '#7030a0'),
         ('adaptive', 'предложенный контур', '#c00000')]
fig, ax = plt.subplots(figsize=(6.6, 3.5))
x = np.arange(3); w = 0.16
for i, (m, n, col) in enumerate(modes):
    v, e = err([(s, m) for s in SCEN])
    ax.bar(x + (i - 2) * w, v, w, label=n, color=col, yerr=e, capsize=2, error_kw=dict(lw=.8))
ax.set_xticks(x); ax.set_xticklabels(SCEN)
ax.set_ylabel('Полезная пропускная способность, rps')
ax.legend(fontsize=7.5, ncol=3, loc='lower center', bbox_to_anchor=(0.5, 1.01), frameon=False)
fig.tight_layout(); fig.savefig(OUT + 'fig2_goodput.png'); plt.close(fig)

# 3. Decisive experiment
pairs = [('adaptive-pinned', 'предложенные правила, пул закреплён', '#f4b183'),
         ('gradient-pinned', 'Gradient, пул закреплён', '#9dc3e6'),
         ('adaptive', 'предложенные правила, пул управляется', '#c00000')]
fig, ax = plt.subplots(figsize=(6.6, 3.4))
w = 0.22
for i, (m, n, col) in enumerate(pairs):
    v, e = err([(s, m) for s in SCEN])
    ax.bar(x + (i - 1) * w, v, w, label=n, color=col, yerr=e, capsize=2, error_kw=dict(lw=.8))
for j, s in enumerate(SCEN):
    gain = G(s, 'adaptive') / G(s, 'adaptive-pinned') - 1
    ax.text(x[j] + w, G(s, 'adaptive') + 7, f'+{gain * 100:.1f} %'.replace('.', ','),
            ha='center', color='#c00000', fontweight='bold', fontsize=8.5)
ax.set_xticks(x); ax.set_xticklabels(SCEN); ax.set_ylim(0, 215)
ax.set_ylabel('Полезная пропускная способность, rps')
ax.legend(fontsize=7.5, ncol=2, loc='lower center', bbox_to_anchor=(0.5, 1.01), frameon=False)
fig.tight_layout(); fig.savefig(OUT + 'fig3_decisive.png'); plt.close(fig)

# 4. Actuator trajectories (one representative repetition)
tr = {}
for r in csv.DictReader(open('docs/benchmark/raw/postgres/actuator-trace.csv')):
    tr.setdefault((r['scenario'], r['mode']), []).append(
        (float(r['t_sec']), int(r['hikari_max_pool']), int(r['concurrency_limit'])))
lines = [('adaptive', 'предложенный контур', '#c00000'),
         ('bottleneck', 'идентификация узкого места', '#7030a0'),
         ('gradient', 'Gradient', '#2e75b6')]
fig, axs = plt.subplots(1, 2, figsize=(6.8, 3.0))
for m, n, col in lines:
    v = tr[('ramp', m)]
    axs[0].step([p[0] for p in v], [p[1] for p in v], where='post', label=n, color=col, lw=1.5)
    axs[1].step([p[0] for p in v], [p[2] for p in v], where='post', label=n, color=col, lw=1.5)
axs[0].axhline(6, color='green', ls='--', lw=1.1)
axs[0].text(1, 6.3, 'максимум по калибровке', color='green', fontsize=7.5)
axs[0].set_ylabel('Размер пула соединений'); axs[1].set_ylabel('Предел конкурентности')
for a in axs:
    a.set_xlabel('Время, с')
axs[0].legend(fontsize=6.8, loc='upper left')
fig.tight_layout(); fig.savefig(OUT + 'fig4_trajectories.png'); plt.close(fig)

# 5. Latency vs shedding, one panel per scenario so the clusters do not overlap
st = [('fixed', 's', '#7f7f7f', 'статический предел'), ('gradient', '^', '#2e75b6', 'Gradient'),
      ('adaptive', 'o', '#c00000', 'предложенный контур')]
fig, axs = plt.subplots(1, 3, figsize=(6.8, 2.6))
for a, s in zip(axs, SCEN):
    for m, mk, col, n in st:
        a.scatter(SH(s, m), P95(s, m), marker=mk, c=col, s=46, label=n, zorder=3)
    a.set_title(s, fontsize=8.5)
    a.set_xlabel('Отклонено, %', fontsize=8)
    a.margins(0.25)
axs[0].set_ylabel('Задержка p95, мс')
h, l = axs[0].get_legend_handles_labels()
fig.legend(h, l, fontsize=7.5, ncol=3, loc='lower center', bbox_to_anchor=(0.5, 0.985), frameon=False)
fig.tight_layout(); fig.savefig(OUT + 'fig5_tradeoff.png', bbox_inches='tight'); plt.close(fig)

# 6. Negative control
a_ = [G(s, 'gradient') for s in SCEN]; b_ = [G(s, 'gradient-pinned') for s in SCEN]
fig, ax = plt.subplots(figsize=(5.4, 2.9))
w = 0.34
ax.bar(x - w / 2, a_, w, label='Gradient, первое измерение', color='#2e75b6')
ax.bar(x + w / 2, b_, w, label='Gradient, второе измерение', color='#9dc3e6')
for j in range(3):
    ax.text(x[j], max(a_[j], b_[j]) + 4, f'{abs(b_[j] / a_[j] - 1) * 100:.1f} %'.replace('.', ','),
            ha='center', fontsize=8)
ax.set_xticks(x); ax.set_xticklabels(SCEN); ax.set_ylabel('Goodput, rps'); ax.set_ylim(0, 200)
ax.legend(fontsize=7.5, loc='lower center', ncol=2, bbox_to_anchor=(0.5, 1.01), frameon=False)
fig.tight_layout(); fig.savefig(OUT + 'fig6_control.png'); plt.close(fig)

# A. Ordering scheme
fig, axs = plt.subplots(2, 1, figsize=(6.4, 3.6), sharex=True)
tt = np.linspace(0, 1, 400); drift = 1 - 0.25 * tt - 0.05 * np.sin(6 * tt)
cols = ['#c00000', '#2e75b6', '#7030a0']
for ax, order, title in [(axs[0], [0] * 7 + [1] * 7 + [2] * 7, 'а) блоками: каждый режим в своём окне времени'),
                         (axs[1], [0, 1, 2] * 7, 'б) чередованием: каждый режим распределён по всему окну')]:
    ax.plot(tt, drift, color='#999999', lw=1.2, ls='--')
    for i, m in enumerate(order):
        xx = (i + 0.5) / len(order)
        ax.scatter(xx, np.interp(xx, tt, drift), color=cols[m], s=26, zorder=3)
    ax.set_title(title, fontsize=8.5, loc='left'); ax.set_ylim(0.6, 1.08); ax.set_yticks([])
hh = [plt.Line2D([], [], marker='o', ls='', color=col, label=f'режим {n}') for n, col in zip('АБВ', cols)]
hh.append(plt.Line2D([], [], ls='--', color='#999999', label='дрейф условий'))
axs[0].legend(handles=hh, fontsize=7, ncol=4, loc='lower left')
axs[1].set_xlabel('Календарное время прогона')
fig.tight_layout(); fig.savefig(OUT + 'figA_ordering.png'); plt.close(fig)

# B. Drift inside one blocked series (re-measurement of 8 September)
v = [126.4, 121.7, 118.6, 114.9, 115.1, 115.5, 115.4]
fig, ax = plt.subplots(figsize=(5.0, 2.7))
ax.plot(range(1, 8), v, 'o-', color='#c00000', lw=1.6, ms=5)
ax.axhline(np.median(v), color='#2e75b6', ls='--', lw=1.1,
           label=f'медиана {np.median(v):.1f} rps'.replace('.', ','))
ax.text(3.6, 121.5, f'−{(1 - v[-1] / v[0]) * 100:.1f} % за серию'.replace('.', ','), fontsize=8.5, color='#555555')
ax.set_xlabel('Номер повтора в серии'); ax.set_ylabel('Goodput, rps'); ax.legend(fontsize=8)
fig.tight_layout(); fig.savefig(OUT + 'figB_series_drift.png'); plt.close(fig)
print('figures written')
