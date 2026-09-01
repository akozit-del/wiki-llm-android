#!/usr/bin/env bash
# Fire only the questions that reach the model, N times over, and dump the log.
#
#   ./benchmark/run_model_turns.sh [serial] [repeats]
#
# run_turn.sh pays 19 fast-path rows at 0.4 s to collect 13 model rows at
# 30-90 s. When what is being measured is the model half — decode tok/s,
# prefill share, or the garbage answers that show up on ~1 turn in 10 — those
# 19 rows buy nothing, and the 25-30 min they stretch the run to is the reason
# a sample never gets repeated. This runs the 13 from model-questions.txt, so a
# sample costs ~8 min and a second one is affordable.
#
# Same preconditions as run_turn.sh: debug build installed, app in the
# FOREGROUND on the chat screen, model loaded, RAG on, ZIM open.
set -euo pipefail

SERIAL="${1:-}"
REPEATS="${2:-1}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

PKG=com.wikillm.android.debug
RECV="$PKG/com.wikillm.android.diag.BenchmarkReceiver"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"${ADB[@]}" logcat -c 2>/dev/null || true
STREAM="$(mktemp -t wikillm-model-turn)"
"${ADB[@]}" logcat -s ChatVM >> "$STREAM" &
TAIL_PID=$!
cleanup() { kill "$TAIL_PID" 2>/dev/null || true; rm -f "$STREAM"; }
trap cleanup EXIT
sleep 1

# Aborting on timeout rather than continuing — see the long note in
# run_turn.sh: send() is a no-op while _generating, so firing into a busy chat
# drops that question silently and shifts every later answer onto the wrong row.
await_end() {
    local want="$1" waited=0 have
    while have=$(grep -c '\[TURN\] end' "$STREAM" 2>/dev/null); [[ "${have:-0}" -lt "$want" ]]; do
        sleep 1
        waited=$((waited + 1))
        if [[ $waited -gt 900 ]]; then
            echo "  turn $want never ended after 15 min — aborting rather than" >&2
            echo "  firing into a busy chat and mislabelling every later answer." >&2
            exit 1
        fi
    done
}

mapfile -t QUESTIONS < <(grep -v '^\s*#' "$HERE/model-questions.txt" | grep -v '^\s*$')

i=0
for ((r = 1; r <= REPEATS; r++)); do
    for q in "${QUESTIONS[@]}"; do
        i=$((i + 1))
        printf '[pass %d] [%2d/%d] %s\n' "$r" "$i" "$((${#QUESTIONS[@]} * REPEATS))" "$q"
        # Nested quoting is mandatory: adb shell hands the whole line to a
        # remote shell, which would otherwise split the Russian on spaces.
        "${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK --es q '$q'" >/dev/null
        await_end "$i"
    done
done

sleep 2
LOG="$HERE/last-model-turns.log"
"${ADB[@]}" shell "run-as $PKG cat files/diag.log" > "$LOG"
echo "diag.log -> $LOG"
python3 "$HERE/score_turn.py" "$LOG" --turns "$i"
