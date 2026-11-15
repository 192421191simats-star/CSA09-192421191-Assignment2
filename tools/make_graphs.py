"""
Builds every graph used in the report from the raw CSV files in QN/data/.
Output: QN/graphs/*.png

Usage: python3 tools/make_graphs.py      (from the repository root, after run_all.sh)
Needs: matplotlib, pandas-free (uses csv module only)
"""
import csv
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
# Fixed categorical order (validated palette); every entity keeps its colour + marker in all charts
SLOTS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
MARKERS = ["o", "s", "^", "D", "v", "P", "X", "*"]
INK, MUTED, GRID = "#2b2b29", "#6b6a64", "#e4e3de"
ENTITY = {}  # name -> slot index (colour follows the entity, never its rank)


def style(name):
    if name not in ENTITY:
        ENTITY[name] = len(ENTITY) % len(SLOTS)
    i = ENTITY[name]
    return dict(color=SLOTS[i], marker=MARKERS[i], markersize=6, linewidth=2)


def setup(ax, title, xlabel, ylabel, logx=False, logy=False):
    ax.set_title(title, fontsize=11, color=INK, loc="left", fontweight="bold")
    ax.set_xlabel(xlabel, color=MUTED, fontsize=9)
    ax.set_ylabel(ylabel, color=MUTED, fontsize=9)
    if logx: ax.set_xscale("log")
    if logy: ax.set_yscale("log")
    ax.grid(True, color=GRID, linewidth=0.8)
    for s in ["top", "right"]: ax.spines[s].set_visible(False)
    for s in ["left", "bottom"]: ax.spines[s].set_color(GRID)
    ax.tick_params(colors=MUTED, labelsize=8)


def save(fig, q, name):
    out = ROOT / q / "graphs"
    out.mkdir(exist_ok=True)
    fig.tight_layout()
    fig.savefig(out / name, dpi=140, facecolor="#fcfcfb")
    plt.close(fig)
    print("  ", (out / name).relative_to(ROOT))


def rows(q, file):
    """Reads a CSV whose FIRST column is a label that may itself contain commas."""
    with open(ROOT / q / "data" / file) as f:
        lines = [l.rstrip("\n") for l in f if l.strip()]
    header = next(csv.reader([lines[0]]))
    out = []
    for l in lines[1:]:
        if l.startswith('"'):
            out.append(dict(zip(header, next(csv.reader([l])))))
            continue
        parts = l.split(",")
        k = len(parts) - (len(header) - 1)               # extra commas belong to the first column
        out.append(dict(zip(header, [",".join(parts[:k])] + parts[k:])))
    return out


def legend(ax):
    ax.legend(frameon=False, fontsize=8, labelcolor=INK)


# ------------------------------------------------------------------ Q1
def q1():
    r = [x for x in rows("Q1", "q1_summary.csv") if x["workload"].startswith("S-")]
    by = defaultdict(list)
    for x in r: by[x["approach"]].append((int(x["fields"]), float(x["mean_ms"]), float(x["alloc_MB_per_run"])))
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for ap, pts in by.items():
        pts.sort()
        axes[0].plot([p[0] for p in pts], [p[1] for p in pts], label=ap, **style(ap))
        axes[1].plot([p[0] for p in pts], [p[2] for p in pts], label=ap, **style(ap))
    setup(axes[0], "Time to build 200 tickets", "fields per ticket (n)", "ms (mean of 10 runs)", True, True)
    setup(axes[1], "Memory allocated per run", "fields per ticket (n)", "MB allocated", True, True)
    legend(axes[0])
    save(fig, "Q1", "q1_scaling.png")


# ------------------------------------------------------------------ Q2
def q2():
    r = rows("Q2", "q2_summary.csv")
    ops = ["add at end", "insert at front", "insert in middle", "get k-th (random k)", "iterate whole list", "remove at front"]
    fig, axes = plt.subplots(2, 3, figsize=(13, 7.5))
    for ax, op in zip(axes.flat, ops):
        by = defaultdict(list)
        for x in r:
            if x["operation"] == op: by[x["structure"]].append((int(x["size"]), float(x["mean_ns_per_op"])))
        for st in ["ArrayList", "LinkedList", "Song[] manual", "ArrayDeque"]:
            if st in by:
                pts = sorted(by[st]); ax.plot([p[0] for p in pts], [p[1] for p in pts], label=st, **style(st))
        setup(ax, op, "playlist size n", "ns per operation", True, True)
    legend(axes[0][0])
    save(fig, "Q2", "q2_operations.png")

    u = rows("Q2", "q2_upnext.csv")
    agg = defaultdict(list)
    for x in u: agg[(x["structure"], int(x["size"]))].append(float(x["ns_per_op"]))
    fig, ax = plt.subplots(figsize=(6.5, 4))
    for st in ["ArrayList", "LinkedList", "ArrayDeque"]:
        pts = sorted((n, sum(v) / len(v)) for (s, n), v in agg.items() if s == st)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], label=st, **style(st))
    setup(ax, "'Up Next' queue: mixed deque operations", "queue size n", "ns per operation", True, True)
    legend(ax)
    save(fig, "Q2", "q2_upnext.png")


# ------------------------------------------------------------------ Q3
def q3():
    r = rows("Q3", "q3_dedup.csv")
    agg = defaultdict(list)
    for x in r: agg[(x["structure"], int(x["unique_students"]))].append(float(x["ns_per_scan"]))
    fig, ax = plt.subplots(figsize=(6.5, 4))
    for st in ["HashSet<Student>", "TreeSet<Student>(by regNo)", "HashMap<String,Student>"]:
        pts = sorted((n, sum(v) / len(v)) for (s, n), v in agg.items() if s == st)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], label=st, **style(st))
    setup(ax, "De-duplication: time per scan", "unique students n (2n scans)", "ns per scan", True, False)
    legend(ax)
    save(fig, "Q3", "q3_dedup.png")


# ------------------------------------------------------------------ Q4
def q4():
    r = rows("Q4", "q4_summary.csv")
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for st in ["HashMap", "TreeMap", "LinkedHashMap", "Hashtable", "SortedArray"]:
        pts = sorted((int(x["n"]), float(x["ns_per_lookup"]), float(x["code_report_ms"])) for x in r if x["structure"] == st)
        axes[0].plot([p[0] for p in pts], [p[1] for p in pts], label=st, **style(st))
        axes[1].plot([p[0] for p in pts], [p[2] for p in pts], label=st, **style(st))
    setup(axes[0], "Average lookup time (100,000 lookups)", "medicines n", "ns per lookup", True, True)
    setup(axes[1], "Code-order report", "medicines n", "ms", True, True)
    legend(axes[0])
    save(fig, "Q4", "q4_maps.png")


# ------------------------------------------------------------------ Q5
def q5():
    r = rows("Q5", "q5_sort.csv")
    agg = defaultdict(list)
    for x in r: agg[(x["approach"], int(x["n"]))].append(float(x["ms"]))
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for ap in sorted({a for a, _ in agg}):
        pts = sorted((n, sum(v) / len(v)) for (a, n), v in agg.items() if a == ap)
        axes[0].plot([p[0] for p in pts], [p[1] for p in pts], label=ap, **style(ap))
    setup(axes[0], "One full ranking", "teams n", "ms", True, True)
    legend(axes[0])
    live = rows("Q5", "q5_live.csv")
    pts = sorted((int(x["n"]), float(x["ms_per_update_and_top10"])) for x in live)
    axes[1].plot([p[0] for p in pts], [p[1] for p in pts], label="ranking() per query", **style("original (Q5)"))
    setup(axes[1], "Live use: 1 update + top-10 query", "teams n", "ms per update", True, True)
    legend(axes[1])
    save(fig, "Q5", "q5_ranking.png")


# ------------------------------------------------------------------ Q6
def q6():
    r = rows("Q6", "q6_growth.csv")
    agg = defaultdict(list)
    for x in r: agg[(x["design"] + " " + x["operation"], int(x["n"]))].append(float(x["ns_per_op"]))
    fig, ax = plt.subplots(figsize=(7.5, 4.4))
    for key in ["PriorityQueue admit", "PriorityQueue next", "SortedLinkedList admit", "FiveFifoQueues admit", "FiveFifoQueues next"]:
        pts = sorted((n, sum(v) / len(v)) for (k, n), v in agg.items() if k == key)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], label=key, **style(key))
    setup(ax, "Triage queue: cost per operation", "patients waiting n", "ns per operation", True, True)
    legend(ax)
    save(fig, "Q6", "q6_growth.png")

    s = rows("Q6", "q6_starvation.csv")
    designs = list(dict.fromkeys(x["design"] for x in s))
    designs = [d for d in designs if "no ageing" not in d]
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.2))
    width = 0.8 / len(designs)
    for i, d in enumerate(designs):
        sub = [x for x in s if x["design"] == d]
        xs = [int(x["severity"]) + (i - (len(designs) - 1) / 2) * width for x in sub]
        st = style(d)
        axes[0].bar(xs, [float(x["mean_wait_min"]) for x in sub], width * 0.92, color=st["color"], label=d)
        axes[1].bar(xs, [int(x["still_waiting"]) for x in sub], width * 0.92, color=st["color"], label=d)
    setup(axes[0], "Mean wait of treated patients", "severity", "minutes")
    setup(axes[1], "Patients never treated in their shift (30 shifts)", "severity", "patients")
    for ax in axes: ax.set_xticks([1, 2, 3, 4, 5]); ax.grid(axis="x", visible=False)
    fig.legend(*axes[0].get_legend_handles_labels(), loc="lower center", ncol=len(designs), frameon=False,
               fontsize=8, labelcolor=INK)
    fig.tight_layout(rect=(0, 0.08, 1, 1))
    out = ROOT / "Q6" / "graphs"; out.mkdir(exist_ok=True)
    fig.savefig(out / "q6_starvation.png", dpi=140, facecolor="#fcfcfb"); plt.close(fig)
    print("  ", "Q6/graphs/q6_starvation.png")


# ------------------------------------------------------------------ Q7
def q7():
    r = rows("Q7", "q7_summary.csv")
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    for d, label in [("original", "original (Q5)"), ("redesign", "redesign")]:
        pts = sorted((int(x["n"]), float(x["mean_ms_per_op"]), float(x["comparisons_per_op"])) for x in r if x["design"] == d)
        axes[0].plot([p[0] for p in pts], [p[1] for p in pts], label=label, **style(label))
        axes[1].plot([p[0] for p in pts], [p[2] for p in pts], label=label, **style(label))
    setup(axes[0], "1 score update + top-10 query", "teams n", "ms per operation", True, True)
    setup(axes[1], "Comparisons per update + query", "teams n", "comparator calls", True, True)
    legend(axes[0])
    save(fig, "Q7", "q7_original_vs_redesign.png")


if __name__ == "__main__":
    for f in (q1, q2, q3, q4, q5, q6, q7):
        ENTITY.clear()                     # each question assigns slots 1, 2, 3 ... in its own fixed order
        try:
            f()
        except FileNotFoundError as e:
            print("   skipped", f.__name__, "-", e)
