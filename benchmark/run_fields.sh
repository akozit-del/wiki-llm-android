#!/usr/bin/env bash
# Sample random articles on the device, record which infobox fields they carry,
# and print the histogram. Needs a debug build with BenchmarkBridge.fieldScan.
#
#   ./benchmark/run_fields.sh [sample] [serial] [seed]
#
# sample defaults to 2000 articles, which takes a couple of minutes — each one
# is a ZIM read plus a jsoup parse.
set -euo pipefail

SAMPLE="${1:-2000}"
SERIAL="${2:-}"
SEED="${3:-1}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

PKG=com.wikillm.android.debug
RECV="$PKG/com.wikillm.android.diag.BenchmarkReceiver"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "sampling $SAMPLE articles (seed $SEED)…"
"${ADB[@]}" shell "am broadcast -n $RECV -a com.wikillm.android.ASK \
  --es m fields --ei k $SAMPLE --el seed $SEED" >/dev/null

# The scan runs on its own coroutine; poll the log for its own done marker
# rather than guessing a duration.
for _ in $(seq 1 400); do
  sleep 3
  if "${ADB[@]}" shell "run-as $PKG cat files/diag.log" 2>/dev/null \
      | grep -q "\[FIELDS\] done"; then
    break
  fi
  printf '.'
done
echo

"${ADB[@]}" shell "run-as $PKG cat files/diag.log" > "$HERE/last-fields.log" 2>/dev/null
python3 "$HERE/score_fields.py" "$HERE/last-fields.log" | tee "$HERE/fields-$(date +%Y-%m-%d).md"
