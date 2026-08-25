#!/usr/bin/env bash
# Fire every question in questions.json at the device in retrieval-only mode
# and score the result. Needs a debug build with BenchmarkBridge.probe (the
# "-es m search" path) installed and the app in the foreground with the ZIM
# available.
#
#   ./benchmark/run_probe.sh [k] [serial]
#
# k = how many ZIM candidates to request (default 20, the app's own default).
set -euo pipefail

K="${1:-20}"
SERIAL="${2:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

PKG=com.wikillm.android.debug
RECV="$PKG/com.wikillm.android.diag.BenchmarkReceiver"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Mark where this run starts, so scoring never picks up a previous run's lines.
MARKER="PROBE-RUN-$(date +%s)"
"${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK --es m search --es q '$MARKER'" >/dev/null
# Probes run on their own coroutine, so a probe still in flight interleaves its
# log lines with the next one and the scorer mis-attributes them. Give the
# marker the same breathing room every question gets (first run lost ib01/ib02
# exactly this way).
sleep 3

mapfile -t QUESTIONS < <(python3 -c "
import json,sys
for q in json.load(open('$HERE/questions.json'))['questions']:
    print(q['question'])
")

i=0
for q in "${QUESTIONS[@]}"; do
    i=$((i + 1))
    printf '[%2d/%d] %s\n' "$i" "${#QUESTIONS[@]}" "$q"
    # Nested quoting is mandatory: adb shell hands the whole line to a remote
    # shell, which would otherwise split the Russian question on spaces.
    "${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK --es m search --ei k $K --es q '$q'" >/dev/null
    # A probe is search + one infobox read; ~1-2 s is enough, and probes are
    # serialised by the ZIM searcher anyway.
    sleep 3
done

sleep 3
LOG="$HERE/last-probe.log"
"${ADB[@]}" shell "run-as $PKG cat files/diag.log" > "$LOG"
echo "diag.log -> $LOG"
python3 "$HERE/score.py" "$LOG" --marker "$MARKER"
