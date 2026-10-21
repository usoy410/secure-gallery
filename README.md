# Secure Gallery

Secure Gallery is an offline, privacy-first Android application developed for team SUDO's NCF BSIT-3B capstone. The app automatically scans device gallery photos for sensitive identity documents (Philippine PhilSys IDs, LTO driver's licenses) and payment cards.

When sensitive documents are detected, Secure Gallery:
1. Generates and saves a solid-fill redacted copy in the photo's original gallery album.
2. Encrypts the untouched original into a hardware-backed, password/biometric-protected local vault.
3. Performs an on-device re-check to guarantee zero residual text or numbers before prompting to delete the original.
4. Provides a **Review Inbox** where users can audit detections, adjust redaction bounding boxes, or mark items as not sensitive.

## Hard Architectural Rules

- **Fully Offline:** The app never requests or merges `android.permission.INTERNET`. All inference models (LiteRT, ML Kit OCR) run strictly on-device.
- **Data Protection:** No raw OCR text or extracted personal field values are ever persisted to Room, local disk, logs, or crash analytics.
- **Safety Guarantee:** Vaulted originals are never removed from public storage until the vault file has been decrypted back, checksum-verified, and the redacted copy re-checked.

---

## Building the App

### Prerequisites
- OpenJDK 21
- Android SDK with API 36 (`compileSdk 36`) and API 34+ build tools

### Build Commands
Navigate to the `android/` directory:

```bash
cd android

# Build debug APK
./gradlew assembleDebug

# Run JVM unit tests and Roborazzi screenshot tests
./gradlew testDebugUnitTest -Proborazzi.test.record=true

# Run Android Lint
./gradlew lint
```

---

## Installing the Debug APK with `adb`

To test Secure Gallery directly on a physical Android device:

### 1. Enable Developer Options & USB Debugging
1. On your Android phone (Android 11+ / API 30+ recommended), navigate to **Settings > About Phone**.
2. Tap **Build Number** 7 times until Developer Mode is enabled.
3. Go to **Settings > System > Developer Options** (or **Additional Settings > Developer Options**).
4. Turn on **USB Debugging** (and **Install via USB** if present on your OEM skin).

### 2. Connect Your Phone
1. Connect the phone to your computer via USB.
2. Accept the "Allow USB debugging?" prompt on your phone screen.
3. Verify that your device is recognized by running:
   ```bash
   adb devices
   ```
   You should see your device listed with the `device` status (e.g. `1234567890ABCDEF device`).

### 3. Build and Install the Debug APK
From the repository root or the `android/` directory:

```bash
# 1. Build the debug APK
cd android
./gradlew assembleDebug

# 2. Install the APK onto the connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Useful `adb` Flags:
- `-r`: Replaces the existing application if already installed, preserving application data and cache.
- `-d`: Allows version code downgrade if testing across branches.
- `-t`: Allows testing packages.

```bash
# Example with all flags:
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

### 4. Launching the App
You can launch the app directly from your phone's app drawer ("Secure Gallery") or via `adb`:

```bash
adb shell am start -n com.sudo.securegallery/.MainActivity
```

---

## Synthetic ID & Card Benchmark Generator (`ml.generate`)

Secure Gallery provides a synthetic data generator for Contribution 3 (§5.3 / §10.1) that renders realistic, fictional Philippine identity documents (PhilSys National ID, ePhilID, LTO driver's licenses) and payment cards with exact field-level ground truth.

### Privacy & Ethics Controls
- **Fictional Demographics:** All names, addresses, and dates are generated using Faker with the `fil_PH` locale.
- **Published Test PANs Only:** Payment card numbers strictly originate from published industry test ranges (e.g., Stripe, Visa, and Mastercard test cards) and pass the ISO/IEC 7812 Luhn checksum.
- **Procedural Avatars:** Portraits are procedural geometric cartoon avatars. Photos of real people are strictly forbidden.
- **Specimen Watermarking:** Every generated card carries a visible diagonal `"SPECIMEN – NOT VALID"` watermark.
- **Deterministic Font Rendering:** Bundled OFL Liberation Sans TrueType fonts guarantee consistent glyph geometry, byte-identical reproduction across operating systems, and >= 95% OCR field evaluation accuracy.
- **Output Storage:** Synthetic datasets output to `data/` (gitignored). Raw or generated images are never committed to git.

### Usage

```bash
# Generate 10 PhilSys physical card samples composited onto varied backgrounds
python -m ml.generate --variant philsys-card --count 10 --seed 42 --out data/synthetic

# Generate 5 samples for every supported D3 template variant
python -m ml.generate --variant all --count 5 --seed 123 --out data/benchmark

# Generate clean, uncomposited card images without background rotation
python -m ml.generate --variant payment-card-front --count 1 --seed 42 --out data/clean --clean

# Apply layout jitter at render time (field offset up to ±2%, font size ±5%, print offset)
python -m ml.generate --variant philsys-card --count 5 --seed 42 --jitter --out data/jittered

# Apply image-quality conditions (blur, glare, rotation, low resolution, perspective, JPEG, crop, occlusion)
python -m ml.generate --variant philsys-card --count 2 --seed 42 --conditions "blur:1,glare:2,perspective:3" --out data/degraded

# Apply all 8 conditions across all 3 severity levels with layout jitter
python -m ml.generate --variant philsys-card --count 1 --seed 42 --jitter --conditions all --out data/rq4
```

### CLI Arguments
- `--variant`: Template variant ID (`philsys-card`, `philsys-ephilid`, `lto-dl-current`, `lto-dl-previous`, `payment-card-front`, `payment-card-back`, or `all`).
- `--count`: Number of images to generate per variant (default: `1`).
- `--seed`: Deterministic integer seed for reproducible, byte-identical output (default: `42`).
- `--out`: Output directory for `.png` image files and `.json` label ground truth (default: `data/synthetic`).
- `--clean`: Disable background compositing to output unrotated, clean card images.
- `--jitter`: Apply layout jitter at render time (field displacement up to ±2% of card width, font size variation within ±5%, and global print offset).
- `--conditions`: Apply named parameterized image degradation conditions. Accepts `all`, condition names (`blur,glare`), or explicit condition:severity pairs (`blur:1,perspective:2,crop:severe`).

### Supported Conditions & Severity Levels (RQ4)
Each condition supports at least 3 severity levels (`1` / `mild`, `2` / `moderate`, `3` / `severe`):
1. **`blur`**: Gaussian defocus blur (radius 2.0, 5.0, 9.0).
2. **`glare`**: Camera flash / specular reflection hotspot with quadratic radial falloff.
3. **`rotation`**: In-plane rotation (6°, 15°, 32°) with canvas expansion and transformed label polygons.
4. **`low_resolution`**: Low-sensor downsampling (2x, 4x, 8x) and bicubic reconstruction.
5. **`perspective`**: 3D projective homography simulating camera viewpoint tilt (IoU ≥ 0.95 with transformed masks).
6. **`jpeg_compression`**: Lossy JPEG quantization artifacts (quality 50, 25, 10).
7. **`crop`**: Field-of-view cropping removing canvas margins and card edges (IoU ≥ 0.95 with cropped masks). Polygons are clipped to image bounds using Sutherland-Hodgman clipping; fields with no visible area inside the crop window are omitted from the label.
8. **`occlusion`**: Simulated holding fingers / thumbs or opaque sticky notes masking card regions.

### Output JSON Label Format
Each generated image `{variant}_{idx:04d}.png` is paired with `{variant}_{idx:04d}.json`. When conditions are applied, condition samples are named `{variant}_{idx:04d}_{condition}_s{severity}.png`. For `crop` conditions, `fields` contains only fields with non-zero visible area inside the cropped viewport, with polygon coordinates clipped to `[0, width]` and `[0, height]`:

```json
{
  "base_image_id": "philsys-card_0000",
  "class": "philsys",
  "condition": "blur",
  "document_class": "philsys",
  "image_path": "philsys-card_0000_blur_s1.png",
  "image_size": [1600, 1200],
  "layout_jitter": true,
  "severity": 1,
  "variant": "philsys-card",
  "card_corners": [[x0, y0], [x1, y1], [x2, y2], [x3, y3]],
  "fields": [
    {
      "name": "Full Name",
      "type": "full_name",
      "sensitive": true,
      "polygon": [[x0, y0], [x1, y1], [x2, y2], [x3, y3]],
      "value": "FORD, KRISTEN, MALDONADO"
    }
  ]
}
```

---

## Dataset Generation with Splits, Negatives, and Failed Redactions (`ml.generate dataset`)

Build complete benchmark datasets partitioned into train/val/test splits with ground truth annotations, negative samples, and failed redaction test cases:

```bash
# Build a complete train/val/test dataset from YAML configuration
python -m ml.generate dataset --config configs/dataset.yaml

# Download curated CC0 / Public Domain background photos for negative set into data/
python -m ml.fetch_photos --out data/photos --count 5
```

### Identity-Based Splits (Zero Data Leakage)
Splits are partitioned strictly by base identity and template seed (`train`, `val`, `test`). All condition variants (blur, glare, rotation, perspective, crop, etc.) and failed-redaction test cases derived from a base image land in the **exact same split**. Disjoint split sets are verified with assertions ensuring no base identity crosses splits.

### Negative Non-ID Sample Categories
To benchmark and minimize detector false positives (§9.3, §10.1):
1. **`receipt`**: Synthetic supermarket/store receipts with thermal receipt typography, line items, and VAT.
2. **`loyalty_card`**: Customer loyalty and membership cards (CR80 form factor without government ID or payment card data).
3. **`blank_card`**: Unprinted plastic PVC cards with standard bevel and shadow.
4. **`card_rectangle`**: Card-shaped rectangular geometries (sticky notes, coasters, business cards).
5. **`screenshot`**: Mobile phone screenshots (chat conversation, notes, settings screen).
6. **`photo`**: Everyday scene photos from a curated CC0 / Public Domain set or procedural fallback.

### Failed Redaction Cases (Test Split Safeguard Audit)
To evaluate on-device redaction re-check safeguards (§9.3):
1. **`complete`**: 100% solid-fill opaque mask covering target fields (`expected_leak: none`).
2. **`partial`**: Redaction box covering only 30%–70% of field area (`expected_leak: partial_text_exposed`).
3. **`misplaced`**: Offset box shifted away from intended field (`expected_leak: offset_uncovered_field`).
4. **`ineffective`**: Semi-transparent fill or blur over field (`expected_leak: transparent_or_blur_fill_legible`).

### Manifest & Datasheet
Every generated dataset includes:
- `manifest.csv`: Tabular index of all images, labels, splits, document classes, variants, degradation conditions, negative types, and redaction defect labels.
- `DATASHEET.md`: Standardized dataset documentation adhering to Gebru et al. (*Datasheets for Datasets*), recording motivation, class and condition distributions, public domain photo licenses, and known gaps.

---

## YOLO11n-Pose Training and LiteRT Export (`ml.train`, `ml.converter`, `ml.export`, `ml.eval`)

On-device detection employs **YOLO11n-pose** at 640 px resolution (§8.2, §8.6).

### Design Change from §8.6
Instead of running a separate OpenCV contour fitting step (`findContours` / polygon approximation) inside the detector box, the detector outputs both the document class and **4 ordered card corner keypoints** (top-left, top-right, bottom-right, bottom-left). The app maps these corners directly to template coordinates via homography (`getPerspectiveTransform`), eliminating OpenCV from the Android runtime.

### Pipeline Commands

```bash
# Optional: Install training and export dependencies
pip install -e '.[train]'

# 1. Convert D7 benchmark labels to Ultralytics YOLO pose format
python -m ml.converter --input data/benchmark --output data/yolo_pose --class-mode document_class

# 2. Smoke training (1 epoch on 50 images in under 10 minutes on CPU)
python -m ml.train --smoke

# 3. Full training
python -m ml.train --data data/yolo_pose/dataset.yaml --epochs 50 --batch 8 --device cpu

# 4. Export to LiteRT (INT8 and float/FP16)
python -m ml.export --model runs/pose/train/weights/best.pt --data data/yolo_pose/dataset.yaml --output runs/export

# 5. Evaluate on validation split (reports per-class P/R/F1, mAP50, and corner distance error)
python -m ml.eval --model runs/pose/train/weights/best.pt --data data/yolo_pose/dataset.yaml --split val
```

See [ml/TRAINING.md](file:///home/usoy/orca/workspaces/secure-gallery/agent-issue-8-yolo-pose-litert/ml/TRAINING.md) for full architecture details, Ultralytics AGPL-3.0 license audit, and LiteRT quantization specifications.


