#!/usr/bin/env bash
# On-device batch export (#29): run the app's real pipeline on a folder of images and pull
# the results back. Needs a connected device or emulator and the debug build of this branch.
#
# Usage: scripts/batch_export.sh <images_dir> <output_dir>
# Example: python -m validation.batch_subset --count 50 --out data/batch_input
#          scripts/batch_export.sh data/batch_input/images data/batch_output
set -euo pipefail
IN=${1:?images folder}
OUT=${2:?output folder}
PKG=com.sudo.securegallery
DIR=/sdcard/Android/data/$PKG/files
ROOT=$(cd "$(dirname "$0")/.." && pwd)

(cd "$ROOT/android" && ./gradlew -q installDebug installDebugAndroidTest)
adb shell rm -rf "$DIR/eval-input" "$DIR/eval-output"
adb shell mkdir -p "$DIR/eval-input"
adb push "$IN/." "$DIR/eval-input/" >/dev/null
echo "Running the pipeline on $(ls "$IN" | wc -l) images..."
adb shell am instrument -w -e class $PKG.eval.BatchExportTest $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/batch_export_instrument.log
grep -q "OK (1 test)" /tmp/batch_export_instrument.log || { echo "Batch export failed (see above)"; exit 1; }
rm -rf "$OUT"
adb pull "$DIR/eval-output" "$OUT" >/dev/null
echo "Results in $OUT (results.json, <id>.jpg, <id>.boxes.json)"
