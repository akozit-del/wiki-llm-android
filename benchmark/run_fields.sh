#!/usr/bin/env bash
# Sample random articles on the device, record which infobox fields they carry,
# and print the histogram. Needs a debug build with BenchmarkBridge.fieldScan.
#
#   ./benchmark/run_fields.sh [sample] [serial] [seed]
#
# sample defaults to 2000 articles; the device does roughly 100/second.
set -euo pipefail

SAMPLE="${1:-2000}"
SERIAL="${2:-}"
SEED="${3:-1}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

PKG=com.wikillm.android.debug
RECV="$PKG/com.wikillm.android.diag.BenchmarkReceiver"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAW="$HERE/last-fields.log"

# diag.log is a bounded ring buffer and a few thousand article lines overflow
# it, so capture logcat as a stream for the whole run instead of reading either
# log afterwards. Start the tail before the broadcast so nothing is missed.
"${ADB[@]}" logcat -c 2>/dev/null || true
: > "$RAW"
"${ADB[@]}" logcat -s BenchmarkBridge >> "$RAW" &
TAIL_PID=$!
cleanup() { kill "$TAIL_PID" 2>/dev/null || true; }
trap cleanup EXIT
sleep 1

echo "sampling $SAMPLE articles (seed $SEED)…"
"${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK \
  --es m fields --ei k $SAMPLE --el seed $SEED" >/dev/null

# Wait for THIS run's marker. A bare "done" also matches a previous scan still
# sitting in the log, which made the runner exit before the new one had written
# anything.
for _ in $(seq 1 600); do
  sleep 2
  if grep -q "\[FIELDS\] done seed=$SEED " "$RAW" 2>/dev/null; then
    break
  fi
  printf '.'
done
echo
cleanup

python3 "$HERE/score_fields.py" "$RAW" "${TOP_N:-200}" \
  | tee "$HERE/fields-$(date +%Y-%m-%d).md"
