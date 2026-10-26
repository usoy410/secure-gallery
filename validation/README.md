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

## Pipeline report and D7 verification (#30)

Score the test-split batch export with:

```bash
python -m validation.report pipeline data/batch_output --labels data/batch_input/labels --out reports/pipeline
```

For recheck sensitivity, prepare a separate folder of synthetic D7 already-redacted images
and their same-stem benchmark JSON labels (including `card_corners`, `redaction.redaction_boxes`,
`is_failed_redaction`, `redaction_type`, and `split`). Optional `<id>.boxes.json` sidecars override
the applied boxes from the benchmark label. Push those labels too: verification needs the applied
boxes and document region. Use distinct IDs for ordinary and D7 images.

```bash
scripts/batch_export.sh data/d7_input data/d7_output d7
python -m validation.report pipeline data/d7_output --labels data/d7_input --out reports/d7 --no-leak
```

The D7 mode runs the on-device recheck directly, without detecting or repairing the redactions.
Its `recheck.csv` scores those outcomes; D7 cases are excluded from detector, field, pipeline,
and exported-copy leak tables because those stages did not run. A D7-only report therefore
has no ordinary pipeline observations. To report both cohorts together, copy `d7_results.json`
alongside an ordinary export's `results.json` and supply a labels folder containing both cohorts.
