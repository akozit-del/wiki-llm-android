#!/usr/bin/env bash
# Fire every question in questions.json at the device as a REAL chat turn —
# fast path, retrieval, prefill and decode included — and report where the wall
# clock went. This is the expensive sibling of run_probe.sh: that one stops
# before the model and costs ~1 s per question, this one pays the full decode
# and costs ~30-60 s per question (roughly 20-30 min for the whole set).
#
#   ./benchmark/run_turn.sh [serial]
#
# Requires: debug build installed, the app in the FOREGROUND on the chat screen
# (the question arrives as a SharedFlow the chat screen collects — nobody
# collects it from the background), a model loaded, RAG on, and the ZIM open.
set -euo pipefail

SERIAL="${1:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

PKG=com.wikillm.android.debug
RECV="$PKG/com.wikillm.android.diag.BenchmarkReceiver"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Same pacing discipline as run_probe.sh, and it matters more here: a turn is
# 0.4 s on the fast path and can be 90 s on a list question, so no fixed sleep
# is right. Watch logcat for this turn's "[TURN] end" instead.
"${ADB[@]}" logcat -c 2>/dev/null || true
STREAM="$(mktemp -t wikillm-turn)"
"${ADB[@]}" logcat -s ChatVM >> "$STREAM" &
TAIL_PID=$!
cleanup() { kill "$TAIL_PID" 2>/dev/null || true; rm -f "$STREAM"; }
trap cleanup EXIT
sleep 1

# Wait until the stream carries at least $1 "[TURN] end" lines. The cap is
# generous (5 min): a stuck turn should be visible in the report as a timeout,
# not silently skipped the way a short wait would skip a slow list question.
await_end() {
    local want="$1" waited=0 have
    while have=$(grep -c '\[TURN\] end' "$STREAM" 2>/dev/null); [[ "${have:-0}" -lt "$want" ]]; do
        sleep 1
        waited=$((waited + 1))
        if [[ $waited -gt 300 ]]; then
            echo "  (timeout waiting for turn $want — continuing)" >&2
            return
        fi
    done
}

mapfile -t QUESTIONS < <(python3 -c "
import json
for q in json.load(open('$HERE/questions.json'))['questions']:
    print(q['question'])
")

i=0
for q in "${QUESTIONS[@]}"; do
    i=$((i + 1))
    printf '[%2d/%d] %s\n' "$i" "${#QUESTIONS[@]}" "$q"
    # Nested quoting is mandatory: adb shell hands the whole line to a remote
    # shell, which would otherwise split the Russian question on spaces.
    "${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK --es q '$q'" >/dev/null
    await_end "$i"
done

sleep 2
LOG="$HERE/last-turn.log"
"${ADB[@]}" shell "run-as $PKG cat files/diag.log" > "$LOG"
echo "diag.log -> $LOG"
python3 "$HERE/score_turn.py" "$LOG" --turns "${#QUESTIONS[@]}"
