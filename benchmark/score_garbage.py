#!/usr/bin/env python3
"""Count the answers that came back as garbage, from a diag.log.

The defect: roughly one model turn in ten returns something like

    Population征收核定 hopeenzahi质量的技术upakan断uterra.scheme …

— not a wrong answer, not a repetition loop, but tokens drawn at random across
scripts, usually running to the 1600-token cap and costing 300+ s. Three phase
runs hit it 1-2 times each and every count so far was made by eye, which is why
"did the sampler reset fix it" could not be answered from the logs.

The test is the script mix, not the length. Every question in the set is
Russian and the prompt says to answer in Russian, so a healthy answer is
overwhelmingly Cyrillic among its letters; Latin appears in names and units and
CJK does not appear at all. Counting letters (not characters) keeps digits,
spaces and punctuation from diluting the ratio in short numeric answers.

    ./benchmark/score_garbage.py benchmark/last-model-turns.log
"""
from __future__ import annotations

import argparse
import re
import sys
import unicodedata

# Below this share of Cyrillic among the answer's letters, the answer is not an
# attempt at Russian. Clean answers in the stored runs sit at 0.85-1.00 (the
# lowest being a chemistry answer thick with Latin formulae); the garbage turns
# sit under 0.25. 0.5 is the gap, not a tuned value.
CYRILLIC_FLOOR = 0.5
# Short answers are excluded from the ratio: «ГАЗ-21» is 40% Latin and fine.
MIN_LETTERS = 40

REPLY = re.compile(r"Reply \((\d+) chars, (\d+) tok, (\d+)ms.*?\): (.*)$")
BEGIN = re.compile(r"\[TURN\] begin q=(.*)$")


def script_mix(text: str) -> tuple[int, int]:
    """Return (cyrillic letters, total letters)."""
    cyr = tot = 0
    for ch in text:
        if not ch.isalpha():
            continue
        tot += 1
        if "CYRILLIC" in unicodedata.name(ch, ""):
            cyr += 1
    return cyr, tot


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--floor", type=float, default=CYRILLIC_FLOOR)
    args = ap.parse_args()

    question = None
    total = 0
    bad = []
    with open(args.log, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = BEGIN.search(line)
            if m:
                question = m.group(1).strip()
                continue
            m = REPLY.search(line)
            if not m:
                continue
            chars, tok, ms, text = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
            total += 1
            cyr, letters = script_mix(text)
            share = cyr / letters if letters else 1.0
            if letters >= MIN_LETTERS and share < args.floor:
                bad.append((question, tok, ms, share, text[:90]))
            question = None

    if not total:
        print("no `Reply (...)` lines in this log — wrong file, or no model turn ran")
        return 1

    print(f"model answers: {total}")
    print(f"garbage answers: {len(bad)}  ({100.0 * len(bad) / total:.0f}%)")
    for q, tok, ms, share, text in bad:
        print(f"  {tok:>5} tok  {ms/1000:>6.0f} s  cyrillic {share:.2f}  {q}")
        print(f"        {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
