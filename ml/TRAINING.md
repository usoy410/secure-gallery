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
