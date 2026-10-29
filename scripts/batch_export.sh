#!/usr/bin/env bash
# On-device batch export (#29): run the app's real pipeline on a folder of images and pull
# the results back. Needs a connected device or emulator and the debug build of this branch.
#
# Usage: scripts/batch_export.sh <images_dir> <output_dir> [pipeline|d7]
# Example: python -m validation.batch_subset --count 50 --out data/batch_input
#          scripts/batch_export.sh data/batch_input/images data/batch_output
set -euo pipefail
IN=${1:?images folder}
OUT=${2:?output folder}
MODE=${3:-pipeline}
case "$MODE" in
  pipeline) INPUT=eval-input; OUTPUT=eval-output; METHOD=exportPipelineResults ;;
  d7) INPUT=eval-d7-input; OUTPUT=eval-d7-output; METHOD=exportD7VerificationResults ;;
  *) echo "Unknown export mode: $MODE (expected pipeline or d7)" >&2; exit 2 ;;
esac
PKG=com.sudo.securegallery
ROOT=$(cd "$(dirname "$0")/.." && pwd)
test -d "$IN" || { echo "Input folder does not exist: $IN" >&2; exit 1; }
test ! -e "$OUT" || { echo "Use a new output directory; preserving existing $OUT" >&2; exit 1; }
COUNT=$(find "$IN" -maxdepth 1 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' \) | wc -l)
test "$COUNT" -gt 0 || { echo "No supported images in $IN" >&2; exit 1; }

(cd "$ROOT/android" && ./gradlew -q installDebug installDebugAndroidTest)
adb shell run-as "$PKG" rm -rf "files/$INPUT" "files/$OUTPUT"
adb shell run-as "$PKG" mkdir -p "files/$INPUT"
tar -C "$IN" -cf - . | adb shell -T run-as "$PKG" tar -xf - -C "files/$INPUT"
LOG=$(mktemp)
STAGED=$(mktemp -d)
trap 'rm -f "$LOG"; rm -rf "$STAGED"' EXIT
echo "Running $MODE export on $COUNT images..."
adb shell am instrument -w -e class "$PKG.eval.BatchExportTest#$METHOD" \
  -e batch_private_storage true -e batch_required true \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$LOG"
grep -q 'OK (1 test)' "$LOG" || { echo "Batch export failed (see above)" >&2; exit 1; }
adb shell run-as "$PKG" test -s "files/$OUTPUT/results.json" || {
  echo "Batch export produced no results.json; the test may have been skipped" >&2; exit 1;
}
adb exec-out run-as "$PKG" tar -cf - -C "files/$OUTPUT" . | tar -xf - -C "$STAGED"
python - "$STAGED/results.json" "$COUNT" <<'PY'
import json
import sys
from pathlib import Path

images = json.loads(Path(sys.argv[1]).read_text())["images"]
if len(images) != int(sys.argv[2]) or len({i["id"] for i in images}) != len(images):
    raise SystemExit("Batch result count/IDs do not match the supplied images")
PY
# Preserve an existing local export until the new export has passed validation.
mkdir -p "$OUT"
cp -a "$STAGED/." "$OUT/"
echo "Results in $OUT"
