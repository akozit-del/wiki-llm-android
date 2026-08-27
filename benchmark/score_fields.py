#!/usr/bin/env python3
"""Build the infobox field histogram from a `[FIELDS]` scan in diag.log.

Answers the question we had been guessing at: how many distinct infobox fields
does the corpus actually use, how concentrated is the distribution, and which of
them can our question patterns already reach.

    adb -s <serial> shell 'run-as com.wikillm.android.debug cat files/diag.log' \
      | python3 benchmark/score_fields.py

Reads stdin, or a file given as the first argument.
"""
import re
import sys
from collections import Counter, defaultdict

# Fields FactoidAnswerer can currently route a question to, as Wikidata property
# ids. Kept here rather than parsed out of Kotlin: this script has to keep
# working when the intent table is edited, and a stale number is worse than an
# explicit one.
WIRED = {
    "P6": "Глава/мэр",
    "P1082": "Население",
    "P2046": "Площадь",
    "P571": "Основан",
    "P36": "Столица",
    "P38": "Валюта",
    "P569": "Дата рождения",
    "P570": "Дата смерти",
    "P19": "Место рождения",
    "P102": "Партия",
    "P39": "Должность",
    "P50": "Автор",
    "P57": "Режиссёр",
}

LINE = re.compile(r"\[FIELDS\] a=\d+\tcard=(\d+)\ttitle=([^\t]*)\tlabels=(.*)$")
DONE = re.compile(r"\[FIELDS\] done articles=(\d+) withCard=(\d+)")


def main() -> None:
    src = open(sys.argv[1], encoding="utf-8", errors="replace") if len(sys.argv) > 1 else sys.stdin
    labels = Counter()          # every label, wikidata id or human text
    pids = Counter()            # wikidata property ids only
    per_card = []               # field count per article that has a card
    pid_labels = defaultdict(Counter)   # property -> visible labels seen for it
    articles = with_card = 0

    # A scan may appear more than once; keep only the last one. But diag.log is
    # a bounded ring buffer — a 2000-article scan overflows it and the begin
    # marker scrolls away, so falling back to "every [FIELDS] line present" is
    # the normal case, not an error. What survives is still a uniform random
    # sample, just a smaller one, so the distribution stays valid.
    lines = [l for l in src if "[FIELDS]" in l]
    truncated = not any("[FIELDS] begin" in l for l in lines)
    if not lines:
        sys.exit("no [FIELDS] lines in the log — run run_fields.sh first")
    if not truncated:
        last = max(i for i, l in enumerate(lines) if "[FIELDS] begin" in l)
        lines = lines[last:]

    # The same run reaches us from both diag.log and logcat; drop duplicates.
    seen_lines = set()
    deduped = []
    for l in lines:
        key = l.split("[FIELDS]", 1)[1].rstrip("\n")
        if key not in seen_lines:
            seen_lines.add(key)
            deduped.append(l)
    lines = deduped

    for line in lines:
        m = LINE.search(line)
        if m:
            articles += 1
            n = int(m.group(1))
            if n:
                with_card += 1
                per_card.append(n)
            for lab in filter(None, m.group(3).rstrip("\n").split("\t")):
                # Rows carrying both come through as `Pxxx=visible label`.
                if "=" in lab and re.match(r"P\d+=", lab):
                    pid, human = lab.split("=", 1)
                    pids[pid] += 1
                    labels[human] += 1
                    pid_labels[pid][human] += 1
                elif re.fullmatch(r"P\d+", lab):
                    pids[lab] += 1
                else:
                    labels[lab] += 1
            continue
        d = DONE.search(line)
        if d:
            articles = max(articles, int(d.group(1)))
            with_card = max(with_card, int(d.group(2)))

    if not articles:
        sys.exit("scan produced no article lines")

    if truncated:
        print(f"note: diag.log ring buffer overflowed; scoring the {articles} "
              f"articles that survived (still a uniform random sample)\n")
    print(f"articles sampled     {articles}")
    print(f"with an infobox      {with_card}  ({with_card / articles:.0%})")
    if per_card:
        per_card.sort()
        print(f"fields per card      median {per_card[len(per_card) // 2]}, "
              f"max {per_card[-1]}")
    print(f"distinct labels      {len(labels)}")
    print(f"distinct wikidata P  {len(pids)}")

    # How concentrated is the tail? This is the number that decides whether
    # wiring fields by hand is tractable at all.
    if pids:
        total = sum(pids.values())
        print("\ncoverage by top-N wikidata properties")
        print("|  N | share of all tagged fields |")
        print("|---|---|")
        running = 0
        for i, (_, c) in enumerate(pids.most_common(), 1):
            running += c
            if i in (10, 25, 50, 100, 150, 200):
                print(f"| {i} | {running / total:.1%} |")
        print(f"| all {len(pids)} | 100% |")

        print("\ntop 40 properties — ✅ = a question can already reach it")
        print("| # | prop | articles | wired |")
        print("|---|---|---|---|")
        for i, (p, c) in enumerate(pids.most_common(40), 1):
            mark = f"✅ {WIRED[p]}" if p in WIRED else ""
            print(f"| {i} | {p} | {c} | {mark} |")

        reachable = sum(c for p, c in pids.items() if p in WIRED)
        print(f"\nwired properties     {len(WIRED)} of {len(pids)} present in the sample")
        print(f"tagged fields we can reach  {reachable / total:.1%}")

    # The table variant 2 is wired from: property, its most common Russian
    # label in this corpus, and how many articles carry it. Printed as Kotlin so
    # it can be pasted into InfoboxExtractor.PRIORITY without retyping.
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    if top_n and pid_labels:
        print(f"\n\n// generated by score_fields.py — top {top_n} properties by article count")
        for p, c in pids.most_common(top_n):
            lab = pid_labels[p].most_common(1)
            if not lab:
                continue
            print(f'        "{p}" to "{lab[0][0]}",'.ljust(48) + f"// {c}")

    human = [(l, c) for l, c in labels.most_common() if not re.fullmatch(r"P\d+", l)]
    if human:
        print("\ntop 25 human labels (rows with no wikidata id)")
        print("| # | label | articles |")
        print("|---|---|---|")
        for i, (l, c) in enumerate(human[:25], 1):
            print(f"| {i} | {l} | {c} |")


if __name__ == "__main__":
    main()
