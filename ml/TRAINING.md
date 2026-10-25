# YOLO11n-Pose Training and LiteRT Export Pipeline

This document details the model architecture, license verification, dataset conversion, training procedures, and LiteRT export pipeline for Secure Gallery's on-device detector (§8.2, §8.6, §20.1 item 3).

## 1. Architectural Design & Changes

### 1.1 Direct Corner Keypoint Regression (Design Change from §8.6)
Rather than executing a two-stage detector followed by OpenCV contour fitting (`findContours` / polygon approx) to locate card corners, the detector employs **YOLO11n-pose** trained at 640 px:
- **Outputs**: Document bounding box, class prediction, plus **4 card corner keypoints** in clockwise order (`top_left`, `top_right`, `bottom_right`, `bottom_left`).
- **Benefit**: Completely eliminates the OpenCV dependency from the mobile app. The 4 corner coordinates map directly to canonical template coordinates via homography (`getPerspectiveTransform`).

### 1.2 Class Strategy: Document Class vs Variant
The dataset converter supports two class granularity modes:
1. `document_class` (Default): 3 classes (`0: philsys`, `1: drivers_license`, `2: payment_card`).
   - *Rationale*: Directly corresponds to the 3 target document classes specified in §8.1. Grouping variants under a common document class provides higher sample density per class for lightweight edge models.
2. `variant`: template-specific classes (e.g. `philsys_front`, `drivers_license_front`, etc.). Useful if template variant disambiguation is desired directly in the detection head.

---

## 2. License Terms and LiteRT Export Verification (§20.1 Item 3)

### 2.1 Ultralytics License (AGPL-3.0)
- **License**: GNU Affero General Public License v3.0 (AGPL-3.0).
- **Compliance**: The Secure Gallery repository is licensed under AGPL-3.0 (`pyproject.toml` and root `LICENSE`). If the application or any derived service is distributed or operated over a network, corresponding source code must be made available under AGPL-3.0.

### 2.2 LiteRT Export Support
- **Backend**: As of Ultralytics 8.4+, `format='tflite'` is unified with Google LiteRT (`com.google.ai.edge.litert`) via the `ai-edge-litert` and `litert-torch` packages.
- **Quantization Support**:
  - **INT8 (`quantize=8`)**: Fully supported with calibration dataset. Produces lightweight models (~3.0 MB) optimized for NPU/GPU/CPU delegates.
  - **INT8 Weights + INT16 Activations (`quantize='w8a16'`)**: Supported for mixed-precision deployment.
  - **Float32 (`quantize=None` / `32`)**: Supported (~6.0 MB).
  - **FP16 (`quantize=16`)**: LiteRT's converter does not support direct `quantize=16` in the current Ultralytics backend. The pipeline automatically exports `w8a16` or standard float LiteRT for half/float requests.
- **Budget Compliance**: Both INT8 (~3.0 MB) and Float (~6.0 MB) are well within the on-device model ceiling of **≤ 10 MB** specified in §8.7.

---

## 3. Pipeline Usage

### 3.1 Dataset Conversion
Convert D7 benchmark annotations to YOLO pose format:
```bash
python -m ml.converter --input data/benchmark --output data/yolo_pose --class-mode document_class
```

### 3.2 Smoke Training (1 Epoch, 50 Images on CPU)
```bash
python -m ml.train --smoke
```
Automatically generates a 50-image subset, trains 1 epoch on CPU in ~15-30 seconds (<10 min target), and exports LiteRT models with metadata JSON.

### 3.3 Full Training
```bash
python -m ml.train --data data/yolo_pose/dataset.yaml --epochs 50 --batch 8 --device cpu
```

### 3.4 Evaluation on Validation Split
```bash
python -m ml.eval --model runs/pose/train/weights/best.pt --data data/yolo_pose/dataset.yaml --split val
```
Reports per-class Precision, Recall, F1, mAP50, and Mean Corner Distance Error (px).

### 3.5 Model Export to LiteRT
```bash
python -m ml.export --model runs/pose/train/weights/best.pt --data data/yolo_pose/dataset.yaml --output runs/export
```

Quantized export errors stop the command; they are never retried as FLOAT32 under an
INT8 filename. Both static INT8 and `w8a16` need calibration data. Keep the held-out
benchmark and device test photos out of that calibration set. The legacy `fp16`
option selects `w8a16` (INT8 weights and INT16 activations), not FP16 weights.

Before copying a quantized export, the exporter checks for scaled INT8 tensors.
FLOAT32 input/output tensors are expected and do not mean the internal weights
are FLOAT32. A filename or JSON quantization label alone is not verification.

---

## 4. Interim Model in the App (#16)

Until the full training run (#9) lands, the app ships an **interim** model so the on-device
detector can be built and tested end to end. It is not the research model: do not report its
numbers as results.

- **Data:** the synthetic benchmark's train split (`data/benchmark`, 468 images after
  conversion), validated on its val split (156 images).
- **Training:** `python -m ml.train --data data/yolo_pose/dataset.yaml --epochs 25 --batch 8`
  on 4 CPU cores, 33 minutes, from the `yolo11n-pose.pt` pretrained weights.
- **Val (synthetic):** box mAP50 0.995 and pose mAP50 0.995 for all three classes. Synthetic val
  images come from the same generator as training, so expect lower numbers on real photos.
- **Export:** INT8 LiteRT, 3.0 MB, copied to `android/app/src/main/assets/detector.tflite`
  (Git LFS) with its metadata as `detector.json`. Thresholds live in `detection.json`.
- **Output format:** `[1, 15, 8400]`, box and keypoint coordinates normalized to the input size
  (`"output_coordinates": "normalized"` in the metadata).
- **Runtime:** the app uses LiteRT 1.4.2 (classic `Interpreter`, `GpuDelegate`). LiteRT 2.x for
  Android needs a newer Kotlin than the app uses; the 1.4 runtime runs this model (checked with
  `ai-edge-litert==1.4.0` in Python).

After replacing the model, regenerate the Android test fixtures and recorded tensors:

```bash
python -m ml.android_fixtures
```

---

## 5. Full Training Run (#9)

The interim model's corners are off by a median 4.6% and up to 8.6% of the card width on the
20 emulator photos. The app's picture margin is 3% (#54), so faces and signatures can keep a
visible strip (PR #64). The full run fixes this with more data, more epochs and a GPU:

- **Data:** `configs/detector_train.yaml`, about 6,000 synthetic cards (40 identities per
  variant, every image condition) with seeds from 500000, so nothing overlaps the benchmark
  (`configs/dataset.yaml`, seed 42) or the emulator fixtures. The benchmark stays the held-out
  evaluation set.
- **Where:** `ml/colab_train.ipynb` on a free Colab T4 GPU, 100 epochs at 640 px. Step-by-step
  guide: `ml/COLAB_GUIDE.md`.
- **Acceptance check:** every corner of the 20 emulator photos within 3% of the card width:

```bash
python -m ml.eval --model <run folder>/weights/best.pt \
  --photos android/app/src/androidTest/assets/redaction --max-corner-pct 3
```

`ml.train` prints the weights path when it finishes; Ultralytics may nest it, for example
`runs/pose/runs/pose/train-2/weights/best.pt`.

`--data <dataset.yaml> --split test` reports the same measure on a split (95th percentile).
