#!/usr/bin/env python3
"""Attribute the wall clock of a full RAG answer to its phases.

Reads the [TURN]/[PHASE] lines ChatViewModel and RagPromptBuilder write to
diag.log and reports where the time of a real answer actually goes:

    factoid attempt -> ZIM search -> article reading -> prefill -> decode

`benchmark/score.py` scores retrieval and stops before the model, which is why
recall@k is well measured and latency is not: the claim that decode is ~90 % of
a RAG turn comes from single hand-timed questions, never from the set. This
script is the missing half — it says nothing about correctness and everything
about time.

    python3 benchmark/score_turn.py last-turn.log [--turns 32]

Phases are summed per turn from the lines between [TURN] begin and [TURN] end,
so a `[PHASE] search=…` written while the turn was still running is billed to
that turn. A turn can hold several [PHASE] lines — the list path builds
excerpts once per candidate biography — and they are summed, not averaged.
"""
from __future__ import annotations  # macOS ships Python 3.9; `str | None` is 3.10+

import argparse
import os
import re
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

BEGIN_RE = re.compile(r"\[TURN\] begin q=(.*)$")
END_RE = re.compile(
    r"\[TURN\] end path=(\S+) total=(\d+)ms factoid=(\d+)ms "
    r"ptok=(\d+) gtok=(\d+) prefill=(\d+)ms decode=(\d+)ms chars=(\d+)"
)
PHASE_RE = re.compile(r"\[PHASE\] search=(\d+)ms read=(\d+)ms docs=(\d+) ctx=(\d+)")
FACTOID_MISS_RE = re.compile(r"\[PHASE\] factoid=miss ms=(\d+)")


def parse(path: str, turns: int | None):
    """Pull turn records out of diag.log, newest run only."""
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()

    records, cur = [], None
    for line in lines:
        m = BEGIN_RE.search(line)
        if m:
            # An interrupted turn never writes its "end" line. Dropping the
            # half-record here rather than carrying it forward keeps a cancelled
            # question from inheriting the next question's phases.
            cur = {
                "question": m.group(1).strip(),
                "search": 0, "read": 0, "ctx": 0, "docs": 0, "phases": 0,
                "done": False,
            }
            records.append(cur)
            continue
        if cur is None:
            continue
        m = PHASE_RE.search(line)
        if m:
            cur["search"] += int(m.group(1))
            cur["read"] += int(m.group(2))
            cur["docs"] += int(m.group(3))
            cur["ctx"] += int(m.group(4))
            cur["phases"] += 1
            continue
        m = END_RE.search(line)
        if m:
            cur.update(
                path=m.group(1), total=int(m.group(2)), factoid=int(m.group(3)),
                ptok=int(m.group(4)), gtok=int(m.group(5)),
                prefill=int(m.group(6)), decode=int(m.group(7)),
                chars=int(m.group(8)), done=True,
            )
            cur = None

    records = [r for r in records if r["done"]]
    # diag.log survives across runs; the caller knows how many questions this
    # run asked, so keep only that many from the tail.
    if turns:
        records = records[-turns:]
    return records


def pct(part: float, whole: float) -> str:
    return f"{100.0 * part / whole:5.1f}%" if whole else "    —"


def med(rows, key) -> float:
    vals = [r[key] for r in rows if key in r]
    return statistics.median(vals) if vals else 0.0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", help="diag.log pulled off the device")
    ap.add_argument("--turns", type=int, default=None,
                    help="how many turns this run asked (keeps the tail)")
    args = ap.parse_args()

    rows = parse(args.log, args.turns)
    if not rows:
        print("no complete [TURN] records found — is this a build with the "
              "turn instrumentation, and did the app have the chat screen in "
              "the foreground?", file=sys.stderr)
        return 1

    fast = [r for r in rows if r["path"] == "factoid"]
    slow = [r for r in rows if r["path"] != "factoid"]

    print(f"turns scored: {len(rows)}  "
          f"(fast path {len(fast)}, model {len(slow)})")
    print()

    if slow:
        # Median, not mean: one 90 s list question would otherwise set the
        # story for all 32.
        m_total = med(slow, "total")
        print(f"model-path turns: {len(slow)}, median total {m_total:.0f} ms")
        print()
        print(f"{'phase':<22}{'median ms':>10}{'share of turn':>16}")
        print("-" * 48)
        for label, key in (
            ("factoid attempt", "factoid"),
            ("ZIM search", "search"),
            ("article reading", "read"),
            ("prefill", "prefill"),
            ("decode", "decode"),
        ):
            v = med(slow, key)
            print(f"{label:<22}{v:>10.0f}{pct(v, m_total):>16}")
        # Whatever the phases don't explain: UI, tokenisation, model warm-up.
        explained = sum(med(slow, k) for k in
                        ("factoid", "search", "read", "prefill", "decode"))
        print(f"{'unattributed':<22}{m_total - explained:>10.0f}"
              f"{pct(m_total - explained, m_total):>16}")
        print()
        print(f"context: median {med(slow, 'ctx'):.0f} chars, "
              f"median {med(slow, 'ptok'):.0f} prompt tokens, "
              f"median {med(slow, 'docs'):.0f} articles")
        print(f"output:  median {med(slow, 'gtok'):.0f} tokens, "
              f"{med(slow, 'chars'):.0f} chars")
        dec_ms, dec_tok = med(slow, "decode"), med(slow, "gtok")
        if dec_ms and dec_tok:
            print(f"decode:  {1000.0 * dec_tok / dec_ms:.1f} tok/s (median)")
        pre_ms, pre_tok = med(slow, "prefill"), med(slow, "ptok")
        if pre_ms and pre_tok:
            print(f"prefill: {1000.0 * pre_tok / pre_ms:.0f} tok/s (median)")
        print()

    if fast:
        print(f"fast path: {len(fast)} turns, median {med(fast, 'total'):.0f} ms "
              f"(factoid lookup {med(fast, 'factoid'):.0f} ms)")
    if slow:
        # The price every slow turn pays for the fast path it did not take.
        miss = [r["factoid"] for r in slow if r["factoid"] > 0]
        if miss:
            print(f"fast-path miss cost: median {statistics.median(miss):.0f} ms "
                  f"on {len(miss)} model-path turns")

    print()
    print("slowest turns:")
    for r in sorted(rows, key=lambda r: -r["total"])[:5]:
        print(f"  {r['total']:>7} ms  [{r['path']:<7}] "
              f"ptok={r['ptok']:<5} gtok={r['gtok']:<4} {r['question'][:48]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
