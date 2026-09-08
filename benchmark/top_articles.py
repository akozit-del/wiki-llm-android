#!/usr/bin/env python3
"""Print the top-4 candidate titles per question from a probe run's diag.log.

Why this exists: recall@k says whether the right article is present, and says
nothing about what the other three excerpt slots are spending themselves on.
The 2026-09-07 full pass showed those slots carrying 36% of the excerpt as
noise, but that number cost a full model pass (~8 min of decode) to get. The
same composition is already visible in a retrieval-only run — `Top hits` is
logged with the four titles that will become the excerpt — so the effect of a
ranking change on excerpt content can be read in the ~2 min a probe run takes.

    ./benchmark/top_articles.py benchmark/before-probe-noise-2026-09-08.log
    ./benchmark/top_articles.py before.log after.log      # side-by-side diff

Top-4, not top-5: RagPromptBuilder logs five but the excerpt takes topK=4.
"""
from __future__ import annotations

import re
import sys

QUERY = re.compile(r"RagPromptBuilder: Query: '(.*?)' -> ZIM keywords:")
TOPHITS = re.compile(r"RagPromptBuilder: Top hits: (.*)$")
TITLE = re.compile(r"^(.*?)\(s=[-\d]+\)")

TOP_K = 4


def parse(path: str) -> list[tuple[str, list[str]]]:
    """Pair each question with the titles of its top hits.

    diag.log is a 256 KB rolling file, so it carries the tail of earlier runs
    with the same questions in it. Last occurrence wins: the newest run is the
    one at the end.
    """
    seen: dict[str, list[str]] = {}
    pending: str | None = None
    for line in open(path, encoding="utf-8", errors="replace"):
        if m := QUERY.search(line):
            pending = m.group(1)
        elif m := TOPHITS.search(line):
            titles = []
            for part in m.group(1).split(" | "):
                t = TITLE.match(part.strip())
                if t:
                    titles.append(t.group(1).strip())
            # A "Top hits" with no preceding Query line lost its question to the
            # roll; skip rather than mis-attribute it to the previous one.
            if pending is not None:
                seen[pending] = titles[:TOP_K]
            pending = None
    return list(seen.items())


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        return 2
    before = parse(sys.argv[1])
    if len(sys.argv) == 2:
        for q, titles in before:
            print(f"{q}\n    {', '.join(titles)}")
        print(f"\n{len(before)} questions, "
              f"{sum(len(t) for _, t in before)} article slots")
        return 0

    after = {q: t for q, t in parse(sys.argv[2])}
    removed = added = 0
    for q, was in before:
        now = after.get(q)
        if now is None:
            print(f"{q}\n    (missing from second run)")
            continue
        gone = [t for t in was if t not in now]
        new = [t for t in now if t not in was]
        removed += len(gone)
        added += len(new)
        if gone or new:
            print(f"{q}\n    -  {', '.join(gone) or '—'}\n    +  {', '.join(new) or '—'}")
    print(f"\n{removed} slots freed, {added} filled by something else")
    return 0


if __name__ == "__main__":
    sys.exit(main())
