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

# Probes run on their own coroutine, so a probe still in flight interleaves its
# log lines with the next question's and the scorer, which reads the log
# sequentially, attributes them to the wrong question. A fixed sleep can't fix
# that: "кто мэр Москвы" took 3.44 s on 2026-08-27 and was silently dropped from
# the table (31/32 scored) because the runner waited 3. So watch logcat instead
# and fire the next question only after this one has printed "[PROBE] done".
"${ADB[@]}" logcat -c 2>/dev/null || true
STREAM="$(mktemp -t wikillm-probe)"
"${ADB[@]}" logcat -s BenchmarkBridge >> "$STREAM" &
TAIL_PID=$!
cleanup() { kill "$TAIL_PID" 2>/dev/null || true; rm -f "$STREAM"; }
trap cleanup EXIT
sleep 1

# Wait until the stream carries at least $1 "done" lines, or ~20 s have passed.
await_done() {
    local want="$1" waited=0
    while [[ $(grep -c '\[PROBE\] done' "$STREAM" 2>/dev/null || echo 0) -lt "$want" ]]; do
        sleep 0.5
        waited=$((waited + 1))
        if [[ $waited -gt 40 ]]; then
            echo "  (timeout waiting for probe $want — continuing)" >&2
            return
        fi
    done
}

# Mark where this run starts, so scoring never picks up a previous run's lines.
MARKER="PROBE-RUN-$(date +%s)"
"${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK --es m search --es q '$MARKER'" >/dev/null
await_done 1

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
    # +1 for the marker probe, which prints a "done" of its own.
    await_done $((i + 1))
done

sleep 2
LOG="$HERE/last-probe.log"
"${ADB[@]}" shell "run-as $PKG cat files/diag.log" > "$LOG"
echo "diag.log -> $LOG"
python3 "$HERE/score.py" "$LOG" --marker "$MARKER"
