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
DIR=/sdcard/Android/data/$PKG/files
ROOT=$(cd "$(dirname "$0")/.." && pwd)

(cd "$ROOT/android" && ./gradlew -q installDebug installDebugAndroidTest)
adb shell rm -rf "$DIR/$INPUT" "$DIR/$OUTPUT"
adb shell mkdir -p "$DIR/$INPUT"
adb push "$IN/." "$DIR/$INPUT/" >/dev/null
echo "Running $MODE export on $(ls "$IN" | wc -l) input files..."
adb shell am instrument -w -e class "$PKG.eval.BatchExportTest#$METHOD" $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/batch_export_instrument.log
grep -q "OK (1 test)" /tmp/batch_export_instrument.log || { echo "Batch export failed (see above)"; exit 1; }
rm -rf "$OUT"
adb pull "$DIR/$OUTPUT" "$OUT" >/dev/null
echo "Results in $OUT"
