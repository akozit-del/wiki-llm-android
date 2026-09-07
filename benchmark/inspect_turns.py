#!/usr/bin/env python3
"""Read a `turns.log` written by TurnDump and line the turns up side by side.

Why this exists: since 2026-09-02 the open question is why three of the thirteen
model questions produce garbage — tokens drawn at random across scripts, running
to the 1600-token cap — while the other ten produce none in ~85 answers. The
per-question tally killed the two even-across-turns hypotheses (sampler drift,
long-run state), and the isolated rerun of «почему Байкал самое глубокое озеро»
came back 0/5 with a byte-identical excerpt. So the excerpt alone does not do it,
and what is left to look at is everything *else* in the model input: the system
prompt and the history in front of the excerpt.

`turns.log` holds that verbatim but is unreadable at a glance — one turn is ~7 KB
and a pass is thirteen of them. This collapses each turn to the numbers the
comparison actually turns on (how much history, how long the excerpt, which
articles it carried, how the answer scored) so a corrupted turn and a clean one
can be diffed by eye in two lines instead of two screens.

    ./benchmark/inspect_turns.py benchmark/last-turns.log
    ./benchmark/inspect_turns.py benchmark/last-turns.log --show 7   # full text

The garbage test is the one from score_garbage.py — the share of Cyrillic among
an answer's letters — kept identical on purpose so the two tools cannot disagree
about which turns were bad. score_garbage.py reads diag.log and counts; this
reads turns.log and explains.
"""
from __future__ import annotations

import argparse
import re
import sys
import unicodedata

PROMPT = re.compile(r"^===== PROMPT (\S+ \S+) q=(.*?) =====$")
REPLY = re.compile(r"^===== REPLY (\S+ \S+) (.*?) =====$")
MSG = re.compile(r"^--- msg\[(\d+)\] (\w+) \((\d+) chars\) ---$")
SYS = re.compile(r"^--- system \((\d+) chars\) ---$")
ARTICLE = re.compile(r"^=== (.+?) ===$")
TOK = re.compile(r"(\d+)\s*tok")

# Same floor and minimum as score_garbage.py — see the note there.
CYRILLIC_FLOOR = 0.5
MIN_LETTERS = 40


class Turn:
    def __init__(self, when: str, question: str):
        self.when = when
        self.question = question
        self.system = ""
        self.messages: list[tuple[str, str]] = []   # (role, content)
        self.reply = ""
        self.reply_meta = ""

    # The excerpt is the last user message: RagPromptBuilder puts the wiki text
    # in the message it just built, and history carries only bare questions.
    @property
    def excerpt(self) -> str:
        for role, content in reversed(self.messages):
            if role == "user":
                return content
        return ""

    @property
    def history(self) -> list[tuple[str, str]]:
        return self.messages[:-1] if self.messages else []

    @property
    def history_chars(self) -> int:
        return sum(len(c) for _, c in self.history)

    @property
    def articles(self) -> list[str]:
        out = []
        for line in self.excerpt.splitlines():
            m = ARTICLE.match(line.strip())
            if m and not m.group(1).startswith(("ВЫДЕРЖКИ", "КОНЕЦ")):
                out.append(m.group(1))
        return out

    @property
    def tokens(self) -> int:
        m = TOK.search(self.reply_meta)
        return int(m.group(1)) if m else 0

    @property
    def cyrillic_share(self) -> float:
        cyr = tot = 0
        for ch in self.reply:
            if not ch.isalpha():
                continue
            tot += 1
            if "CYRILLIC" in unicodedata.name(ch, ""):
                cyr += 1
        self._letters = tot
        return cyr / tot if tot else 1.0

    @property
    def garbage(self) -> bool:
        share = self.cyrillic_share
        return self._letters >= MIN_LETTERS and share < CYRILLIC_FLOOR


def parse(path: str) -> list[Turn]:
    turns: list[Turn] = []
    cur: Turn | None = None
    sink: list[str] | None = None      # where following text lines accumulate
    role = ""

    def flush() -> None:
        nonlocal sink, role
        if cur is not None and sink is not None:
            text = "\n".join(sink).strip("\n")
            if role == "system":
                cur.system = text
            elif role == "reply":
                cur.reply = text
            elif role:
                cur.messages.append((role, text))
        sink, role = None, ""

    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.rstrip("\n")
            m = PROMPT.match(line)
            if m:
                flush()
                cur = Turn(m.group(1), m.group(2))
                turns.append(cur)
                continue
            m = REPLY.match(line)
            if m and cur is not None:
                flush()
                cur.reply_meta = m.group(2)
                sink, role = [], "reply"
                continue
            m = SYS.match(line)
            if m and cur is not None:
                flush()
                sink, role = [], "system"
                continue
            m = MSG.match(line)
            if m and cur is not None:
                flush()
                sink, role = [], m.group(2)
                continue
            if sink is not None:
                sink.append(line)
    flush()
    # A rolling file can open mid-record; a turn with no reply is in flight.
    return [t for t in turns if t.reply_meta]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--show", type=int, help="print turn N in full (1-based)")
    ap.add_argument("--since", help="only turns at or after this timestamp")
    args = ap.parse_args()

    turns = parse(args.log)
    if args.since:
        turns = [t for t in turns if t.when >= args.since]
    if not turns:
        print("no complete turns in this file")
        return 1

    if args.show:
        t = turns[args.show - 1]
        print(f"===== turn {args.show}  {t.when}  q={t.question}")
        print(f"--- system ({len(t.system)}) ---\n{t.system}")
        for i, (role, content) in enumerate(t.messages):
            print(f"--- msg[{i}] {role} ({len(content)}) ---\n{content}")
        print(f"--- reply {t.reply_meta} ---\n{t.reply}")
        return 0

    print(f"{'#':>3} {'time':<12} {'msgs':>4} {'hist':>6} {'exc':>6} "
          f"{'tok':>5} {'cyr':>5}  question")
    bad = 0
    for i, t in enumerate(turns, 1):
        mark = "GARBAGE" if t.garbage else ""
        if t.garbage:
            bad += 1
        print(f"{i:>3} {t.when.split()[1][:8]:<12} {len(t.messages):>4} "
              f"{t.history_chars:>6} {len(t.excerpt):>6} {t.tokens:>5} "
              f"{t.cyrillic_share:>5.2f}  {t.question[:44]:<44} {mark}")
    print(f"\nturns: {len(turns)}   garbage: {bad} "
          f"({100.0 * bad / len(turns):.0f}%)")

    # The articles are what the excerpt actually carried. A question that keeps
    # producing garbage with a stable article list points at the context; one
    # whose list changes between a clean and a dirty turn points at retrieval.
    print("\narticles per turn:")
    for i, t in enumerate(turns, 1):
        flag = "!" if t.garbage else " "
        print(f"{i:>3}{flag} {', '.join(t.articles) or '(none)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
