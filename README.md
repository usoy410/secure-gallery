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
```

### CLI Arguments
- `--variant`: Template variant ID (`philsys-card`, `philsys-ephilid`, `lto-dl-current`, `lto-dl-previous`, `payment-card-front`, `payment-card-back`, or `all`).
- `--count`: Number of images to generate per variant (default: `1`).
- `--seed`: Deterministic integer seed for reproducible, byte-identical output (default: `42`).
- `--out`: Output directory for `.png` image files and `.json` label ground truth (default: `data/synthetic`).
- `--clean`: Disable background compositing to output unrotated, clean card images.

### Output JSON Label Format
Each generated image `{variant}_{idx:04d}.png` is paired with `{variant}_{idx:04d}.json`:

```json
{
  "class": "philsys",
  "document_class": "philsys",
  "variant": "philsys-card",
  "image_path": "philsys-card_0000.png",
  "image_size": [1600, 1200],
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

