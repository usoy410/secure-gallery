# Model card: Secure Gallery card detector

Issue #9 candidate for review. Desktop geometry and Kotlin post-processing checks pass;
Android device inference, performance, and redaction/leak checks remain pending. Results
below use synthetic images only and are not evidence of real-world privacy protection.

## Model

| Property | Value |
|---|---|
| Architecture | YOLO11n-pose, Ultralytics 8.4.183, 3 classes, 4 ordered corners |
| Classes | `philsys`, `lto`, `payment_card` |
| Input | FLOAT32 `[1, 3, 640, 640]`, RGB, letterboxed, scaled to `[0, 1]` |
| Output | FLOAT32 `[1, 15, 8400]`, normalized box and corner coordinates |
| Internal precision | W8A16: INT8 weights with INT16 activations, not full static INT8 |
| Asset | `android/app/src/main/assets/detector.tflite`, Git LFS |
| File size | 3,242,595 bytes (3.09 MiB), within the 10 MiB target |
| SHA256 | `f1d5637d50deedeb9194bfd4490e68175bd1b3b8215b9f5bc3d50bd9837fb7b4` |
| License | AGPL-3.0 (Ultralytics) |

The JSON metadata identifies the internal precision as `w8a16`. FLOAT32 graph input/output
is intentional and matches the app's buffer interface. The runtime compatibility and speed
of the INT16 internal operations still require Android device validation.

## Training and export provenance

The source is `best.pt` in the user-provided `detector_model.zip`. It was not retrained
for this update. Its SHA256 is
`2f6856f0dbcfcb38fa59d0be09e99688cd8c71b7fa93214f258399655d69b4bb`.
The checkpoint and ZIP should remain in external artifact storage, not Git.

| Property | Available evidence |
|---|---|
| Training configuration | 60 epochs, batch 32, 640 px, seed 0; see `training-args.yaml` |
| Starting weights | `yolo11n-pose.pt`, as recorded in the training arguments |
| Training data | Colab path `data/detector_train_yolo/dataset.yaml`; original dataset and image counts were not supplied |
| Training duration | Final `results.csv` row records 9,587.51 seconds |
| Training source commit / finish time | Not supplied; `run_info.json` was absent |
| Training hardware | Colab Tesla T4 reported in the supplied console output; no machine-readable run record |
| Export toolchain | CPU PyTorch 2.14.1, torchvision 0.29.1, litert-torch 0.10.0, LiteRT 2.3.0, ai-edge-quantizer 0.10.0 |
| Calibration | 300 independently generated synthetic cards, seeds 600000–600299; no benchmark or device-fixture images |

Detailed evidence is in [`reports/detector/issue-9`](../reports/detector/issue-9/README.md).
The new calibration set was generated for this export; it is not a restoration of the
original Colab calibration data. The uploaded file named `detector.tflite` was actually
FLOAT32, had no embedded pose metadata, and exceeded the size target. It is not the
candidate committed here.

## Results on the 20 synthetic device fixtures

All three models use the same fixed fixture commit
`3eb72eb0b1bd81aea77cce8f986bfc8142b4637f`, 640 px, CPU, square letterbox,
confidence 0.25 and NMS IoU 0.7. Each error is the worst corner's distance as a percentage
of card width. The unchanged acceptance limit is 3% on every photo.

| Model | Median | p95 | Maximum | Missed | Photos exceeding 3% |
|---|---:|---:|---:|---:|---:|
| Source `best.pt` | 1.47% | 2.71% | 3.05% | 0 | 1 |
| Uploaded FLOAT32 export | 1.47% | 2.71% | 3.05% | 0 | 1 |
| New full static INT8 | 3.39% | 5.79% | 6.11% | 0 | 13 |
| **Selected W8A16 candidate** | **1.58%** | **2.45%** | **2.79%** | **0** | **0** |

The stock `ml.eval` CLI passes the candidate's corner gate without metadata recovery.
The worst photo remains `r05_payment-card-front.jpg`, at 2.786326139% before rounding.
The Kotlin `RecordedTensorTest` also passes against five regenerated synthetic recordings.
These checks do not replace the Android device redaction/leak test.

## Supplied benchmark results: PyTorch weights only

The ZIP's `eval_benchmark_test.json` reports the following synthetic benchmark test results.
They were not regenerated in this update and must not be attributed to the W8A16 export.

| Class | Precision | Recall | mAP50 | Mean corner error (px) |
|---|---:|---:|---:|---:|
| philsys | 0.9831 | 1.0000 | 0.9945 | 10.28 |
| lto | 1.0000 | 1.0000 | 0.9950 | 8.90 |
| payment_card | 1.0000 | 1.0000 | 0.9950 | 8.17 |
| All | | | 0.9948 (mAP50-95: 0.9914) | 9.12 |

Benchmark corner error: median 1.39%, p95 4.77%, maximum 17.72% over 174 detected card
instances. Re-evaluation of the selected W8A16 model on the full benchmark remains pending.

## Limits and remaining checks

- The 20 fixture results are synthetic geometry checks, not real device captures or leak-test results.
- Android LiteRT 1.4.2 inference, delegate fallback, device performance, and end-to-end
  redaction/leak validation remain pending. Desktop LiteRT 2.3.0 success does not prove them.
- The original training commit, dataset counts, and completion timestamp cannot be recovered
  from the supplied ZIP and are not inferred.
- `detection.json` thresholds and the 3% picture margin are unchanged.
- This is a review candidate; do not treat issue #9 or its dependent leak checks as complete.
