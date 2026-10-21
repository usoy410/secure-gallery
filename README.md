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
