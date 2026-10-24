# Validation

Desktop tools that judge the app's output. Each folder has its own tests; `docs/TESTING.md`
says what each proves.

| Folder | What it does |
|---|---|
| `localize/` | Desktop reference localization: OCR-first, layout-only, hybrid (#10). |
| `leaktest/` | Leak test: can a field be read back from a redacted image (#11–#13)? |
| `report/` | RQ4 report and charts (#14). |

## On-device batch export (#29)

ML Kit and LiteRT only run on Android, so the app's real pipeline results come from the
phone or emulator. A debug-only instrumented test (`android/.../eval/BatchExportTest.kt`)
runs detection, localization, redaction and the recheck on a folder of images, one at a time,
and writes:

- `<id>.jpg`: the redacted copy as saved, and `<id>.boxes.json`: its boxes;
- `results.json`: per image the class, confidence, corners, state, boxes, retries, recheck
  result, timings per stage (`decode`, `detect`, `ocr_localize`, `recheck`,
  `hash_redact_save`, `total`, in ms) and `peak_memory_mb`. No OCR text.

Steps, with a device or emulator connected (`adb devices`):

```bash
python -m ml.generate dataset --config configs/dataset.yaml       # if data/benchmark is missing
python -m validation.batch_subset --count 50 --out data/batch_input
scripts/batch_export.sh data/batch_input/images data/batch_output
python -m validation.leaktest data/batch_output --labels data/batch_input/labels --out data/batch_leaks
```

`batch_subset` keeps the labels on the desktop; only the images go to the device. The export
also runs nightly in CI on 50 test images (artifact `batch-export`).

The export is not part of the app: it lives in the test APK, and `EvalNotInAppTest` fails if
it ever moves into the app's own code.
