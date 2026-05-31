#!/usr/bin/env python3
"""Parse [mcts-stats] log lines and report aggregate distributions.

Usage: analyze_mcts_stats.py LOG [LOG ...]
"""
import math
import re
import statistics
import sys
from collections import Counter

STATS_RE = re.compile(
    r"\[mcts-stats\] tree-depth: mean=(?P<mean>[-+0-9.eE]+) "
    r"std=(?P<std>[-+0-9.eE]+) max=(?P<max>\d+) "
    r"\(n=(?P<n>\d+) playouts\) reached-end-of-game: (?P<endpct>[-+0-9.eE]+)%"
    r"(?: \| root(?:-visits)?: (?P<visits>[^|]+))?"
)
VISIT_RE = re.compile(
    r"(?P<move>\S+?)=(?P<n>\d+)\((?P<pct>[-+0-9.eE]+)%(?:,Q=(?P<q>[-+0-9.eE]+))?\)"
)


def parse_line(line):
    m = STATS_RE.search(line)
    if not m:
        return None
    rec = {k: float(v) for k, v in m.groupdict(default="").items() if k not in ("visits",) and v}
    rec["n"] = int(rec["n"])
    rec["max"] = int(rec["max"])
    rec["visits"] = []
    if m.group("visits"):
        for vm in VISIT_RE.finditer(m.group("visits")):
            q = float(vm.group("q")) if vm.group("q") else None
            rec["visits"].append((vm.group("move"), int(vm.group("n")), float(vm.group("pct")), q))
    return rec


def percentile(sorted_xs, p):
    if not sorted_xs:
        return float("nan")
    k = (len(sorted_xs) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_xs[int(k)]
    return sorted_xs[f] + (sorted_xs[c] - sorted_xs[f]) * (k - f)


def summary_stats(xs, name):
    if not xs:
        print(f"  {name}: no data")
        return
    xs_sorted = sorted(xs)
    mean = statistics.fmean(xs)
    std = statistics.pstdev(xs) if len(xs) > 1 else 0.0
    print(f"  {name}: mean={mean:.2f} std={std:.2f} "
          f"min={xs_sorted[0]:.2f} p50={percentile(xs_sorted, 0.5):.2f} "
          f"p90={percentile(xs_sorted, 0.9):.2f} p99={percentile(xs_sorted, 0.99):.2f} "
          f"max={xs_sorted[-1]:.2f} (n={len(xs)})")


def entropy_bits(percentages):
    """Shannon entropy in bits over a list of percentages (0-100 scale)."""
    h = 0.0
    for p in percentages:
        if p > 0:
            f = p / 100.0
            h -= f * math.log2(f)
    return h


def main(paths):
    records = []
    for path in paths:
        with open(path) as f:
            for line in f:
                rec = parse_line(line)
                if rec is not None:
                    records.append(rec)
    print(f"Parsed {len(records)} [mcts-stats] lines across {len(paths)} log file(s)")
    if not records:
        return

    # Per-line aggregates
    line_means = [r["mean"] for r in records]
    line_maxes = [r["max"] for r in records]
    line_ends = [r["endpct"] for r in records]
    line_ns = [r["n"] for r in records]

    print("\n== Tree-depth distribution (one observation per move) ==")
    summary_stats(line_means, "per-move mean depth")
    summary_stats(line_maxes, "per-move max depth")

    # Playout-weighted aggregate stats (treat each playout as a unit)
    total_playouts = sum(line_ns)
    weighted_depth_mean = sum(r["mean"] * r["n"] for r in records) / total_playouts
    weighted_end_pct = sum(r["endpct"] * r["n"] for r in records) / total_playouts
    print(f"\n== Playout-weighted aggregate (total playouts: {total_playouts}) ==")
    print(f"  weighted-mean depth across all playouts: {weighted_depth_mean:.2f}")
    print(f"  weighted-mean reached-end-of-game across all playouts: {weighted_end_pct:.2f}%")

    print("\n== Reached-end-of-game per move ==")
    summary_stats(line_ends, "per-move reached-end-of-game %")
    # Buckets
    buckets = [(0, 5), (5, 25), (25, 50), (50, 75), (75, 100), (100, 101)]
    print("  per-move reached-end-of-game histogram:")
    for lo, hi in buckets:
        c = sum(1 for x in line_ends if lo <= x < hi)
        bar = "#" * int(50 * c / max(1, len(line_ends)))
        print(f"    [{lo:3d}-{hi:3d}%): {c:5d} ({100*c/len(line_ends):5.1f}%) {bar}")

    print("\n== Per-move playout budget distribution ==")
    summary_stats(line_ns, "playouts per move")

    # Root-visit concentration
    top1_shares = []
    top2_shares = []
    n_candidates = []
    entropies = []
    decisive_count = 0  # top1 >= 60%
    split_count = 0      # top2 <= top1 + 5 and both >= 30
    close_call_q_deltas = []  # |Q_top1 - Q_top2| for close-call positions
    for r in records:
        if not r["visits"]:
            continue
        # Sort by visit pct desc
        moves_sorted = sorted(r["visits"], key=lambda v: -v[2])
        pcts = [v[2] for v in moves_sorted]
        top1_shares.append(pcts[0])
        top2_shares.append(sum(pcts[:2]))
        n_candidates.append(len(pcts))
        entropies.append(entropy_bits(pcts))
        if pcts[0] >= 60:
            decisive_count += 1
        if len(pcts) >= 2 and pcts[1] >= 30 and (pcts[0] - pcts[1]) <= 10:
            split_count += 1
            q1, q2 = moves_sorted[0][3], moves_sorted[1][3]
            if q1 is not None and q2 is not None:
                close_call_q_deltas.append(abs(q1 - q2))

    print("\n== Root-visit concentration ==")
    summary_stats(top1_shares, "top-1 move visit share %")
    summary_stats(top2_shares, "top-2 moves combined %")
    summary_stats(n_candidates, "# candidate moves")
    summary_stats(entropies, "visit entropy (bits)")

    total_with_visits = len(top1_shares)
    print(f"  decisive (top1 >= 60%):        {decisive_count} ({100*decisive_count/total_with_visits:.1f}%)")
    print(f"  close call (top2 within 10pp): {split_count} ({100*split_count/total_with_visits:.1f}%)")

    # Classify close calls by Q delta
    if close_call_q_deltas:
        print("\n== Close-call classification by |Q_top1 - Q_top2| ==")
        summary_stats(close_call_q_deltas, "|Q delta| in close calls")
        bins = [(0, 0.5), (0.5, 2.0), (2.0, 5.0), (5.0, 10.0), (10.0, math.inf)]
        n_cc = len(close_call_q_deltas)
        print("  histogram of |Q delta| among close calls (points on 0-157 scale):")
        for lo, hi in bins:
            c = sum(1 for d in close_call_q_deltas if lo <= d < hi)
            bar = "#" * int(50 * c / max(1, n_cc))
            label = f"[{lo:5.1f}-{hi:5.1f})" if hi != math.inf else f"[{lo:5.1f}-  inf)"
            print(f"    {label}: {c:5d} ({100*c/n_cc:5.1f}%) {bar}")
        equiv = sum(1 for d in close_call_q_deltas if d < 2.0)
        genuine = sum(1 for d in close_call_q_deltas if d >= 2.0)
        all_moves = len(records)
        print(f"\n  outcome-equivalent (|dQ| < 2pts):   {equiv} ({100*equiv/n_cc:.1f}% of close calls, {100*equiv/all_moves:.1f}% of all moves)")
        print(f"  genuine close call (|dQ| >= 2pts):  {genuine} ({100*genuine/n_cc:.1f}% of close calls, {100*genuine/all_moves:.1f}% of all moves)")
        print(f"  (genuine close calls are the PUCT-addressable subset)")

    # Top-share histogram
    print("\n  top-1 share histogram:")
    bins = [(0, 30), (30, 45), (45, 55), (55, 70), (70, 85), (85, 101)]
    for lo, hi in bins:
        c = sum(1 for x in top1_shares if lo <= x < hi)
        bar = "#" * int(50 * c / max(1, total_with_visits))
        print(f"    [{lo:3d}-{hi:3d}%): {c:5d} ({100*c/total_with_visits:5.1f}%) {bar}")

    # Stratify depth & end-of-game by playout budget (proxy for game stage)
    print("\n== Stratified by playout budget tertile (proxy for game stage) ==")
    sorted_ns = sorted(line_ns)
    t1 = percentile(sorted_ns, 1.0 / 3)
    t2 = percentile(sorted_ns, 2.0 / 3)
    print(f"  tertile cuts: low<={t1:.0f}  mid<={t2:.0f}  high>{t2:.0f}")
    for tag, lo, hi in [("low (later moves)", -1, t1), ("mid", t1, t2), ("high (earlier moves)", t2, math.inf)]:
        subset = [r for r in records if lo < r["n"] <= hi]
        if not subset:
            continue
        d_mean = sum(r["mean"] * r["n"] for r in subset) / sum(r["n"] for r in subset)
        e_mean = sum(r["endpct"] * r["n"] for r in subset) / sum(r["n"] for r in subset)
        ts = [v for r in subset for v in [max((p for _, _, p, _ in r["visits"]), default=0)]]
        ts_mean = statistics.fmean(ts) if ts else float("nan")
        print(f"  {tag:25s}: n={len(subset):5d}  mean-depth={d_mean:5.2f}  "
              f"reached-end={e_mean:5.1f}%  top1-share={ts_mean:5.1f}%")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
