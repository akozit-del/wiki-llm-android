#!/usr/bin/env python3
"""Score a retrieval-only probe run against benchmark/questions.json.

Reads the [PROBE] lines BenchmarkBridge writes to diag.log and reports the
metrics the reference set exists for: recall@k, fast-path hit-rate,
false-fast-rate and search latency.

Only expect_article is used as ground truth. It is a stable assertion (the
mayor of Togliatti lives in the article "Тольятти" whatever the snapshot says
his name is today), so scoring cannot become circular the way comparing against
our own generated answers would.

    python3 benchmark/score.py last-probe.log [--marker PROBE-RUN-123]
"""
from __future__ import annotations  # macOS ships Python 3.9; `str | None` is 3.10+

import argparse
import json
import os
import re
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RANKS = (1, 3, 5, 10, 20)

Q_RE = re.compile(r"\[PROBE\] q=(.*)$")
FAST_RE = re.compile(r"\[PROBE\] fast=(hit|miss) ms=(\d+)(?: article=(.*?) field=(.*?) value=(.*))?$")
CAND_RE = re.compile(r"\[PROBE\] cand=(\d+) ms=(\d+) titles=(.*)$")


def norm(title: str) -> str:
    """Titles differ only cosmetically between the log and the reference set."""
    return title.replace("ё", "е").replace("Ё", "Е").strip().lower()


def parse(path: str, marker: str | None):
    """Pull probe records out of diag.log, newest run only."""
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    if marker:
        for i in range(len(lines) - 1, -1, -1):
            if marker in lines[i]:
                lines = lines[i:]
                break
    records, cur = [], None
    for line in lines:
        m = Q_RE.search(line)
        if m:
            cur = {"question": m.group(1).strip(), "fast": None, "titles": []}
            records.append(cur)
            continue
        if cur is None:
            continue
        m = FAST_RE.search(line)
        if m:
            cur["fast_ms"] = int(m.group(2))
            if m.group(1) == "hit":
                cur["fast"] = {"article": (m.group(3) or "").strip(),
                               "field": (m.group(4) or "").strip(),
                               "value": (m.group(5) or "").strip()}
            continue
        m = CAND_RE.search(line)
        if m:
            cur["cand"] = int(m.group(1))
            cur["search_ms"] = int(m.group(2))
            cur["titles"] = [t.strip() for t in m.group(3).split(" | ") if t.strip()]
    return {r["question"]: r for r in records}


def same_article(expect: str, got: str) -> bool:
    """Match a reference title against a title as the searcher reports it.

    ru.wiki inverts person titles ("Гагарин, Юрий Алексеевич") but the searcher
    can hand back the short form it resolved through ("Гагарин"). Scoring those
    as different articles marked three correct fast answers as false-fast on the
    first baseline run — the values were Gagarin's real birthplace and
    Mendeleev's real birth date. Accepting the surname stem is deliberately
    lenient (the city "Гагарин" would pass too); the alternative was mislabelling
    right answers as the worst failure class we track.
    """
    e, g = norm(expect), norm(got)
    return e == g or ("," in e and e.split(",")[0].strip() == g)


def rank_of(expect: str, titles: list[str]) -> int | None:
    for i, t in enumerate(titles, start=1):
        if same_article(expect, t):
            return i
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--marker", default=None)
    ap.add_argument("--json", action="store_true", help="dump per-question rows as JSON")
    args = ap.parse_args()

    questions = json.load(open(os.path.join(HERE, "questions.json"), encoding="utf-8"))["questions"]
    probed = parse(args.log, args.marker)

    rows, missing = [], []
    for q in questions:
        rec = probed.get(q["question"])
        if rec is None or "cand" not in rec:
            missing.append(q["id"])
            continue
        rank = rank_of(q["expect_article"], rec["titles"])
        fast = rec.get("fast")
        rows.append({
            "id": q["id"],
            "category": q["category"],
            "question": q["question"],
            "expect_article": q["expect_article"],
            "rank": rank,
            "candidates": rec["cand"],
            "search_ms": rec.get("search_ms"),
            "fast_ms": rec.get("fast_ms"),
            "fast_article": fast["article"] if fast else None,
            "fast_value": fast["value"] if fast else None,
            # A fast answer read out of the wrong article is the worst failure
            # mode there is: confident, instant and wrong.
            "false_fast": bool(fast) and not same_article(q["expect_article"], fast["article"]),
        })

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0

    n = len(rows)
    if n == 0:
        print("no probe records matched questions.json — was the run fired in search mode?")
        return 1

    print(f"scored {n}/{len(questions)} questions" + (f"  (no data: {', '.join(missing)})" if missing else ""))
    print()
    print("| metric | value |")
    print("|---|---|")
    for k in RANKS:
        hit = sum(1 for r in rows if r["rank"] and r["rank"] <= k)
        print(f"| recall@{k} | {hit}/{n} = {100 * hit / n:.0f}% |")
    mrr = sum(1 / r["rank"] for r in rows if r["rank"]) / n
    print(f"| MRR | {mrr:.3f} |")

    ib = [r for r in rows if r["category"] == "infobox"]
    ib_fast = [r for r in ib if r["fast_article"]]
    if ib:
        print(f"| fast-path hit-rate (infobox) | {len(ib_fast)}/{len(ib)} = {100 * len(ib_fast) / len(ib):.0f}% |")
    fast_all = [r for r in rows if r["fast_article"]]
    print(f"| fast-path coverage (all) | {len(fast_all)}/{n} = {100 * len(fast_all) / n:.0f}% |")
    bad = [r for r in fast_all if r["false_fast"]]
    if fast_all:
        print(f"| false-fast-rate | {len(bad)}/{len(fast_all)} = {100 * len(bad) / len(fast_all):.0f}% |")
    ms = [r["search_ms"] for r in rows if r["search_ms"] is not None]
    if ms:
        print(f"| search latency median / p90 | {statistics.median(ms):.0f} ms / {sorted(ms)[int(0.9 * (len(ms) - 1))]} ms |")

    print()
    print("| id | cat | rank | cand | search ms | fast | question |")
    print("|---|---|---|---|---|---|---|")
    for r in rows:
        rank = r["rank"] if r["rank"] else "MISS"
        fast = "—"
        if r["fast_article"]:
            fast = ("WRONG:" if r["false_fast"] else "") + r["fast_article"]
        print(f"| {r['id']} | {r['category']} | {rank} | {r['candidates']} | "
              f"{r['search_ms']} | {fast} | {r['question']} |")

    miss = [r for r in rows if not r["rank"]]
    if miss:
        print()
        print(f"lost articles ({len(miss)}) — not in top {max(RANKS)}, unrecoverable downstream:")
        for r in miss:
            print(f"  {r['id']}  want «{r['expect_article']}»  got: {', '.join(t for t in probed[r['question']]['titles'][:5])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
