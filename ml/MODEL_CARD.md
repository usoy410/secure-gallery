# Model card: Secure Gallery card detector

Fill in every `TODO` from the Colab zip (`ml/MODEL_UPDATE_GUIDE.md`, step 6). Numbers are on synthetic data only; do not present them as real-photo results.

## Model

| | |
|---|---|
| Architecture | YOLO11n-pose (Ultralytics 8.4.183), 3 classes, 4 corner keypoints (TL, TR, BR, BL) |
| Classes | `philsys`, `lto`, `payment_card` |
| Input | 640 x 640, letterboxed |
| Format in the app | LiteRT, INT8, `android/app/src/main/assets/detector.tflite` (Git LFS) |
| File size | TODO MB |
| License | AGPL-3.0 (Ultralytics) |

## Training run

| | |
|---|---|
| Repo commit | TODO (`run_info.json` → `repo_commit`) |
| Hardware | TODO (`run_info.json` → `gpu`), Google Colab |
| Epochs, batch | TODO |
| Finished (UTC) | TODO |
| Training time | TODO (from the notebook's training cell) |
| Training data | `configs/detector_train.yaml`: synthetic cards, 40 identities per variant, every image condition, seeds from 500000. TODO: number of train / val images (printed by the notebook) |
| Starting weights | `yolo11n-pose.pt` (COCO keypoints) |

## Results

Benchmark test split (`configs/dataset.yaml`, seed 42, held out from training), from `eval_benchmark_test.json`:

| Class | Precision | Recall | mAP50 | Mean corner error (px) |
|---|---|---|---|---|
| philsys | TODO | TODO | TODO | TODO |
| lto | TODO | TODO | TODO | TODO |
| payment_card | TODO | TODO | TODO | TODO |
| All | | | TODO (mAP50-95: TODO) | TODO |

Corner error as a percent of the card width (the app's picture margin is 3%):

| Set | Median | p95 | Max | Missed |
|---|---|---|---|---|
| Benchmark test split | TODO | TODO | TODO | n/a |
| 20 emulator photos, full-precision weights | TODO | TODO | TODO | TODO |
| 20 emulator photos, INT8 `detector.tflite` | TODO | TODO | TODO | TODO |
| Interim model (#16), INT8, for comparison | 4.60 | 8.33 | 8.55 | 0 |

## Limits

- Trained and tested on generated cards only. Real photos have other lighting, wear and backgrounds, so expect lower numbers there.
- Thresholds (`detection.json`) are still the §8.2 placeholders until tuned on val (M3).
