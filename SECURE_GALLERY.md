# Secure Gallery: Offline Gallery Privacy Reviewer for Philippine IDs and Payment Cards

**Document type:** BSIT capstone concept, system design, and RRL/RRS source register (one combined document)
**Team:** SUDO, NCF BSIT-3B
**Platform:** Android (Kotlin)
**Status:** Planning. Design decisions agreed 20 October 2026. No code yet. For adviser and group review.
**Research orientation:** Applied IT solution, computer vision, OCR, privacy-focused software development, and quantitative system evaluation

This file replaces four earlier documents: the concept draft, the 90-citation RRL matrix, the consolidated RRL/RRS inventory, and the design notes. Where they overlapped, their contents were merged. Where the agreed design changed the concept, the concept text was revised.

## Contents

| Part | Sections |
|---|---|
| A. Concept | 1 Overview · 2 Title · 3 Problem · 4 Solution · 5 Gap and contribution · 6 Research questions · 7 Objectives |
| B. System design | 8 System design · 9 Evaluation · 10 Dataset and ethics · 11 Scope |
| C. Project management | 12 Cost · 13 BSIT/SDG alignment · 14 Outputs · 15 Risks |
| D. Sources | 16 Related systems (RRS) · 17 Related literature (RRL, 90 citations) · 18 Additional sources · 19 Dataset leads · 20 Verification checklist |

---

# Part A. Concept

## 1. Project Overview

The proposed project is an Android application that protects gallery images containing selected Philippine identification documents and payment cards. The app scans every image on the device without any network connection. When it detects a supported document, it automatically:

1. encrypts the original into a password-protected in-app vault;
2. saves a solid-color redacted copy in the original's album, so other gallery apps show only the redacted version;
3. removes the original from the public gallery after a single batched Android delete confirmation;
4. re-checks the saved redacted file for residual sensitive information.

Inside the app, an **eye toggle** switches between the redacted copy and the original while the vault is unlocked. A **review inbox** lets the user correct, extend, or undo any automatic redaction. Because the original is always kept in the vault, every automatic action can be reversed.

The project is not intended to guarantee complete privacy or to identify every kind of sensitive content. The first version supports three document classes: **PhilSys National ID, LTO driver's license, and payment card**.

The research contributions are three, detailed in §5:

1. **Layout-anchored hybrid redaction.** Fields are covered at their known position on the card even when OCR cannot read them. This is compared against the OCR-first redaction used by existing tools.
2. **A leak-test framework for photo redaction.** It is run on this app and on existing Android redaction apps.
3. **A public synthetic Philippine ID benchmark and generator.**

The vault, the eye toggle, and gallery scanning are the system that delivers these contributions, not contributions in themselves. Passing the validation is a safeguard and a measurement. It is not a proof that every form of sensitive information has been removed.

## 2. Proposed Title

**Offline Gallery Privacy Reviewer for Philippine IDs and Payment Cards**

Alternative, if the adviser prefers a title that foregrounds the research evaluation:

**Evaluating Offline Detection and Redaction of Philippine ID and Payment Card Images on Android**

Alternative that names the algorithmic contribution:

**Layout-Anchored Offline Redaction of Philippine ID Images on Android with Leak-Tested Validation**

All three titles are below the 15-word limit. Confirm the final wording with the adviser before using it in the proposal.

## 3. Problem and Rationale

Phone galleries can contain photographs of identification cards, payment cards, invoices, receipts, and other documents that show personal or financial information. Such images can be exposed if a device is shared, lost, accessed by another person, or if an image is accidentally forwarded or posted. Users may not remember which images contain these details, and manually inspecting a large gallery can be inconvenient.

Existing software provides parts of this workflow. Some tools identify potentially sensitive images, some extract text using OCR, and others let users blur or cover selected regions. However, features and supported document types vary. Public product descriptions do not always establish whether a tool supports Philippine-specific documents, performs full-gallery scanning, works entirely offline, or checks whether a redaction was correctly applied. These gaps must be treated as matters for systematic comparison and testing, not as proof that no existing tool has the capability.

For a BSIT capstone, the problem is therefore framed as an applied software and computer-vision problem: how to implement an on-device workflow that detects a limited set of Philippine ID and payment-card images, redacts their sensitive fields automatically even when the image is too degraded to read, and verifies that the redacted output does not leak those fields.

### 3.1 Problem Statement

Existing redaction tools locate sensitive fields by reading them, so a field that OCR cannot read in a blurred or glare-affected photo is left uncovered. Whether existing Android photo-redaction tools leave recoverable information behind has also not been measured. There is a need to evaluate whether an offline Android application can reliably identify selected Philippine identification and payment-card images and redact their sensitive fields across image-quality conditions, while keeping the original recoverable and minimizing the handling of personal data. There is also a need for a repeatable way to test whether any photo-redaction tool's output still leaks those fields.

### 3.2 Specific Problems

1. The system may miss supported documents or incorrectly flag ordinary images and visually similar cards.
2. OCR may fail to read selected sensitive fields in images affected by blur, glare, rotation, low resolution, perspective distortion, or partial obstruction.
3. Automatic redaction regions may be too small, misplaced, or incomplete, so the user needs a way to correct them afterwards without losing the original.
4. A visual check or OCR re-scan alone cannot prove that all sensitive information has been removed. Redactions can also leak through channels other than the visible pixels: embedded EXIF thumbnails, partially covered characters, non-opaque fills, metadata, and leftover copies. The system needs clearly defined, limited verification criteria that cover these channels.
5. Processing time, usability, and offline operation must be evaluated on an actual Android device to determine whether the workflow is practical.

## 4. Proposed Solution

Develop an Android app that scans the gallery images permitted by the user and identifies supported document images. It then locates sensitive fields **by layout first**: it maps the card's known template onto the photo, then refines the boxes with on-device OCR and field rules. It then redacts those fields automatically with opaque solid-color fills, vaults the original, and re-checks the output. Detections with medium confidence are still redacted, but they are placed at the top of the review inbox marked "needs check." In the inbox, the user can move, resize, or add regions, or mark the image "not sensitive." Each correction re-renders the redacted copy from the vaulted original.

The app does not upload images or OCR text. It does not store extracted text, it masks values in the interface, and it keeps only the minimum local state needed. All models are bundled in the APK. The app has no `INTERNET` permission and is tested in airplane mode after a clean install.

### 4.1 Main Features

| Feature | Description |
|---|---|
| Gallery permission and scan | Uses Android's media permission and explains it clearly. On Android 14+, if only selected photos are granted, the app scans just those. Runs a first full scan, then processes new images in the background. |
| Document detection | Detects PhilSys ID, LTO driver's license, and payment card. |
| Layout-anchored field localization | Fits the card's corners, maps the class template's field regions onto the photo with a homography (a perspective transform), and refines them with OCR and field rules. Fields are covered even when they cannot be read. |
| Automatic redaction | Applies opaque solid fills to image pixels. If the card's corners cannot be found and OCR also fails, covers the whole document region. |
| Redacted copy in public gallery | Saves the flattened redacted copy in the original's album, keeping the capture date and stripping GPS and device metadata. |
| Secure vault and eye toggle | Stores the encrypted original. Switches between the redacted copy and the original after unlock (PIN/password, biometric, recovery code). |
| Review inbox | Lists automatic redactions, with "needs check" items first. Users can move, resize, or add regions, or mark an image "not sensitive." |
| Post-redaction check | Re-opens the saved file, checks that the fills are opaque, and re-runs OCR. If a field is still detected, it enlarges the region and retries up to twice, then falls back to whole-document fill and marks the image "needs check." |
| Vault export / import | Saves an encrypted backup file to a folder the user picks, unlockable with the recovery code. |
| Privacy controls | No cloud processing, no persistent OCR text, local-only records, and screenshots blocked while an original is shown. |
| Scan summary | Totals of scanned, redacted, needs-check, and vaulted images, without the sensitive values. |

### 4.2 System Workflow

```text
START
  |
  v
User grants gallery access (or selects photos on Android 14+)
  |
  v
Background scan: load each image locally, one at a time
  |
  v
Detect supported document / card region
  |
  +---- Low confidence ----> Mark as not detected by this pipeline
  |                          (not proof that image has no sensitive data)
  v
Fit card corners, map template field regions onto the photo (layout-first)
  |
  v
Refine regions with on-device OCR + field rules (expand, never shrink)
  |
  +---- Corners not found AND fields unreadable ----> Cover the whole document
  v
Encrypt original into vault, verify by decrypting it back
  |
  v
Apply opaque solid-color fill, save redacted copy (JPEG)
  |
  v
Recheck the saved copy (pixel opacity + OCR)
  |
  +---- Still detected ----> Enlarge region, retry (max 2),
  |                          then whole-document fill + "needs check"
  v
Insert redacted copy in the original's album
  |
  v
Queue original for removal --> user approves one batched system delete dialog
  |
  v
Review inbox: user may correct regions, mark "not sensitive", or toggle the original
  |
  v
END
```

### 4.3 Key Design Principle

**The system acts automatically; the user can correct; the original is never lost.** Redaction happens without a confirmation step. But the original is always encrypted and verified in the vault first, so any automatic action can be undone or corrected from the review inbox. A "not detected" result is limited to the implemented checks and must not be worded as a guarantee that the image is safe.

## 5. Research Gap and Contribution

Existing systems demonstrate sensitive-image classification, gallery privacy auditing, OCR-based field detection, and image redaction (§16, §17). Gallery scanning, solid-fill redaction, and encrypted vaults are therefore **not** claimed as new. The study contributes three measurable things.

### 5.1 Contribution 1: Layout-anchored hybrid redaction (algorithm)

**Gap:** OCR-first tools find a field by reading it. When a photo is blurred or has glare, OCR fails and the field stays exposed. This was observed in the team's PrivyMark test (§16), and RollGuard's own site warns about it.

**Approach:** Philippine IDs have fixed layouts. The app fits the card's four corners, computes a homography to the class template, and covers the template's field regions, so a field is redacted **without needing to be read**. OCR is then used only to enlarge regions (never shrink them) and to check the result.

**Prior work:** template-based field localization already exists for *reading* ID documents, for example MIDV2020 rectified field localization and Matalov & Arlazarov (2025), single-image template generation (§18.2). Using it for *privacy redaction under degradation*, compared against OCR-first redaction, was not found in the reviewed sources.

**Experiment:** OCR-first vs layout-first vs hybrid, across the image conditions in §10.1. Measured by leak rate (§9.2) against over-redacted area.

### 5.2 Contribution 2: A leak-test framework for photo redaction, applied to existing tools

**Gap:** redaction audits exist for PDF tools ("Story Beyond the Eye," PoPETs 2023, tested 11 tools) and for mosaiced or blurred text (Hill et al., PoPETs 2016). No audit of Android photo-redaction apps was found, and Theme 1 gap #2 (§17.2) notes that no study automatically checks the exported phone image.

**Approach:** an offline suite (§9.5) that tries to recover redacted fields through specific leak channels: visible pixels, partially covered characters, non-opaque fills, embedded EXIF thumbnails, metadata (compare ALIBIS, 2026), card-number structure, and leftover copies.

**Experiment:** run the suite on this app **and** on PrivyMark, Skry, GalleryKeeper, and any other comparable Android tools, using the same mock-ID set. This turns the related-systems comparison into measured evidence.

### 5.3 Contribution 3: A public synthetic Philippine ID benchmark

**Gap:** Theme 2 gaps #1 and #5 (§17.3): no public dataset has field-level labels for Philippine IDs.

**Approach:** release the template generator and dataset: fictional data, exact field boxes, condition variants, and failed-redaction cases. No real IDs are included.

### 5.4 Supporting system

Automatic gallery-wide redaction that replaces the public copy, the encrypted vault with eye toggle, the review inbox, and the on-device recheck. These are the engineering through which the contributions are delivered and measured.

These claims are provisional. Revise them after hands-on testing of the closest systems and a full literature search on template-based redaction and redaction auditing (§20).

## 6. Research Questions

1. **What are the functional, technical, and privacy requirements** for an offline Android application that automatically detects and redacts selected Philippine ID and payment-card images and keeps the originals in a secure vault?
2. **How effectively does the document-detection component identify and localize the supported document classes,** in terms of precision, recall, F1-score, false-positive rate, and localization performance?
3. **How accurately does the OCR and field-rule pipeline identify selected sensitive fields,** in terms of field-level precision, recall, F1-score, and the proportion of inconclusive results?
4. **How do OCR-first, layout-first, and hybrid field localization compare** in leak rate and over-redacted area under blur, glare, rotation, low resolution, perspective distortion, and partial obstruction? How often are the whole-document fallback and the recheck-and-retry triggered under each condition?
5. **How accurately do the automatic redaction regions cover the intended sensitive fields?** This covers redaction coverage, the frequency of missed, partial, or misplaced redactions, and the rate at which users had to correct them in the review inbox.
6. **How effectively does the post-redaction check identify deliberately incomplete or failed redactions?** This is measured using verification sensitivity, false-negative rate, false-positive rate, and inconclusive rate, both on the device and with an independent desktop OCR engine.
7. **How much recoverable information do existing Android photo-redaction tools and this app leave in their output,** measured per leak channel with the leak-test framework?
8. **What are the application's usability and operational performance levels,** in terms of task completion, time per image, processing latency, memory/resource use on a 4 GB RAM device, and user evaluation?

Question 1 is a requirements/design question. Question 4 is the algorithmic core (Contribution 1). Question 7 is the tool audit (Contribution 2). Questions 2, 3, 5, and 6 evaluate the pipeline. Question 8 evaluates practical use and system quality.

## 7. Objectives of the Study

### 7.1 General Objective

To develop and evaluate an offline Android gallery privacy app that redacts selected Philippine identification and payment-card images through layout-anchored hybrid localization, keeps the originals in an encrypted vault, and validates its output with a leak-test framework that is also applied to existing redaction tools.

### 7.2 Specific Objectives

1. To identify the functional, technical, and privacy requirements for the proposed application.
2. To build and publicly release a synthetic Philippine ID and payment-card generator and dataset with field-level ground truth, controlled image-quality variations, negative examples, and failed-redaction cases.
3. To implement and evaluate a document-detection component using precision, recall, F1-score, false-positive rate, and localization metrics.
4. To implement and evaluate an on-device OCR and field-rule pipeline using field-level precision, recall, F1-score, and inconclusive rate.
5. To implement layout-anchored hybrid field localization and compare it with OCR-first and layout-only localization by leak rate and over-redacted area under each image condition.
6. To implement automatic solid-fill redaction with a review inbox, and measure coverage, placement accuracy, and user correction rate.
7. To implement an encrypted vault with an eye toggle, verified write-before-delete, and vault export/import.
8. To evaluate the post-redaction check, on the device and in an independent desktop harness, using complete, partial, misplaced, and ineffective redaction cases.
9. To build a leak-test framework covering pixel, partial-character, opacity, EXIF-thumbnail, metadata, number-structure, and leftover-copy channels, and apply it to this app and to existing Android photo-redaction tools.
10. To assess usability and operational performance on a 4 GB RAM Android device, including task completion, processing time, and resource use.

---

# Part B. System Design

## 8. System Design

### 8.1 Scope Decisions

- **Android:** minSdk 30 (Android 11), targetSdk 35, Kotlin.
- **Media scanned:** every image in MediaStore, including camera, screenshots, downloads, and Messenger, WhatsApp, and Viber folders. Videos and GIFs are excluded. Images the app created itself are skipped.
- **Gallery type:** privacy-focused. The app shows only flagged and redacted items, the vault, and scan status. It does not replace a full gallery app.
- **Offline:** no `INTERNET` permission in the merged manifest, all models bundled, and verified in airplane mode after a clean install.

**Fields redacted:**

| Class | Redacted | Left visible |
|---|---|---|
| PhilSys ID | PCN/PSN, full name, date of birth, address, face photo, QR code, signature | Card design, headings |
| LTO driver's license | License number, name, date of birth, address, face photo, signature | Sex, height, weight, headings |
| Payment card | Card number, cardholder name, expiry date, CVV (if the back is shown) | Bank logo, card network logo |

### 8.2 Pipeline

```
MediaStore image (new or not yet scanned)
  ├─ decode, downsampled (640 px for the detector; long side ≤ 2048 px for OCR)
  ├─ YOLO11n (LiteRT) → class + box + confidence
  │     └─ low (< 0.35) → "not detected by this pipeline", stop
  ├─ fit the card's 4 corners inside the detector box (contour / quadrilateral fit)
  │     └─ homography to the class template → template field regions mapped onto the photo
  ├─ ML Kit text recognition (bundled) on each mapped region → lines + boxes (in memory only)
  ├─ field rules (regex + layout per class) → final box = union(template region, OCR box)
  │     ├─ corners not found → OCR-first field rules only
  │     └─ corners not found AND fields unreadable → one box over the whole document
  │     (payment cards: bank layouts vary, so OCR-first + Luhn check, with template only
  │      for the standard number / expiry band)
  ├─ confidence level:
  │     high   (doc ≥ 0.6 AND field rules matched) → automatic, state REDACTED
  │     medium (doc 0.35–0.6 OR some fields missing) → automatic, state NEEDS_CHECK
  ├─ SAFE ORDER:
  │     1. encrypt original into the vault
  │     2. decrypt it back and compare the hash (stop and keep the original if they differ)
  │     3. draw solid fills on the full-resolution bitmap and save as JPEG q95
  │     4. on-device recheck (§8.5); if it fails, grow the box by 15% and redact again,
  │        up to 2 times, then whole-document fill + NEEDS_CHECK
  │     5. insert the redacted copy into MediaStore (same RELATIVE_PATH, keep EXIF date)
  │     6. queue the original for batched deletion
  └─ notification "N photos ready to secure" → one createDeleteRequest dialog
        ├─ allowed → VAULTED
        └─ denied  → ORIGINAL_STILL_PUBLIC (banner, can retry)
```

- **Thresholds:** 0.35 and 0.6 are placeholders, tuned on the validation split only.
- **Deletion:** originals are permanently deleted (`createDeleteRequest`), not sent to the system trash. Trash would leave the unredacted file readable for 30 days, and the vault already provides undo.
- **EXIF metadata:** the app strips GPS, device, maker-note, and thumbnail tags. It keeps `DateTimeOriginal` so other galleries show the copy on the right date, and applies the orientation tag to the pixels.
- **Background work:** WorkManager runs the first full scan, then picks up new images through a content URI trigger. It only runs while charging or when the battery isn't low, and processes one image at a time. The delete dialog needs an Activity, so deletions are queued and the user is notified to approve them.

### 8.3 Vault and Eye Toggle

- **Encryption:** each original is stored as a separate file in app-private storage, encrypted with AES-256-GCM using a random data key (DEK). The DEK itself is stored encrypted ("wrapped") three ways, one per unlock method:
  1. **Biometric:** wrapped by an Android Keystore key that requires `BiometricPrompt` authentication.
  2. **App PIN/password:** wrapped by PBKDF2-HMAC-SHA256(PIN), mixed with a Keystore-held HMAC key. That ties brute-forcing to the device. Wrong attempts are rate-limited.
  3. **Recovery code:** a random 24-character code shown once at setup. The DEK is wrapped by PBKDF2(code).
- **Session:** one unlock lets the eye toggle switch freely. The vault re-locks when the app goes to the background or after 2 minutes idle. `FLAG_SECURE` blocks screenshots while an original is on screen.
- **Thumbnails of originals** are decrypted into memory only, never written to disk.
- **Forgotten PIN:** use biometric or the recovery code. If all three are lost, the originals cannot be recovered, and setup says so.
- **Uninstall:** Android wipes app-private storage. Export Vault is the mitigation, and the app warns about this the first time the vault is used.
- **Cloud backups:** the app cannot remove copies already backed up by Google Photos or other cloud services. Setup says so once, and each vaulted item shows a "may still exist in cloud backups" note.
- `androidx.security:security-crypto` is deprecated and is not used.

### 8.4 Review Inbox and Item States

States: `NOT_DETECTED`, `REDACTED`, `NEEDS_CHECK`, `VAULTED`, `ORIGINAL_STILL_PUBLIC`, `RESTORED`, `FAILED` (if any step fails, the original is kept untouched).

| User action | Effect |
|---|---|
| Move or resize a box, add a box | Re-render from the vaulted original, then overwrite the app's own redacted copy. No system prompt, because the app owns that file. |
| "Not sensitive" | Write the original back to its album, delete the redacted copy, remove from the vault. State becomes `RESTORED`. |
| Eye toggle | Switch between the redacted copy and the original (vault must be unlocked) |

Every correction is counted (without any content) for the correction-rate metric. **Room (the local database) stores** the MediaStore ID, content hash, state, class, confidences, box coordinates, field types, and retry counts. **It never stores OCR text or field values,** and neither do logs or crash reports.

### 8.5 On-Device Recheck

Both checks must pass:

1. **Pixel check:** inside each box, at least 99.5% of pixels are within 8 of the fill colour on every channel. This is done on the saved JPEG, read back from disk.
2. **Text check:** ML Kit is run again on the document region. No field rule may match, and no run of 4 or more digits from a redacted number may reappear.

### 8.6 Algorithms and Technical Approach

| Pipeline stage | Method | Selection note |
|---|---|---|
| Document localization/classification | YOLO11n, trained with Ultralytics, exported to LiteRT (INT8 or FP16) | Select using held-out precision, recall, F1/mAP, model size, and latency. Ultralytics is AGPL-3.0, so the repo is AGPL. |
| Preprocessing | Resize, grayscale, contrast, perspective correction | Compare with a no-preprocessing baseline. Do not assume preprocessing helps. |
| Text extraction | ML Kit Text Recognition v2, bundled Latin model | Verify offline behavior and the merged manifest. |
| Corner fit + homography | OpenCV contour/quadrilateral fit inside the detector box, `getPerspectiveTransform` to the template | Template per card design variant (§15). Compare against corner-keypoint regression only if the contour fit fails often. |
| Field localization | Layout-first template regions, unioned with OCR + regex boxes (hybrid) | Compared against OCR-first and layout-only (RQ4). Regions are only ever expanded. Picture fields (face, QR code, signature) get a margin of 3% of the card width on every side, because no OCR box can correct their position (#54). |
| Redaction rendering | `Bitmap`/`Canvas` opaque solid fill, flattened JPEG | Test that covered pixels are actually replaced, not hidden by the UI. |
| Post-redaction check | §8.5 on the device; independent OCR on the desktop (§9.2) | A limited check, not a proof of complete privacy. |
| Augmentation | Blur, rotation, glare, compression, perspective, crop, occlusion | All versions of one base image stay in the same split. |

### 8.7 Performance Targets (4 GB RAM device)

| Measure | Target |
|---|---|
| Image that is not a document (detector only) | ≤ 150 ms median |
| Flagged image, full pipeline | ≤ 1.5 s median |
| First scan, 1,000 photos | < 5 min, in the background |
| Detector model size | ≤ 10 MB |

**How the app stays within 4 GB:**
- **Downsampled decoding:** images are decoded smaller than full size (`inSampleSize` / `ImageDecoder` target size).
- **One image at a time:** the worker never holds more than one full-resolution bitmap.
- **No `largeHeap`:** the app doesn't request Android's larger memory allowance.
- **Hardware acceleration:** the GPU delegate is tried first, with a CPU fallback.

All latency figures are measured on the real phone. Emulator timings don't count.

### 8.8 Technology Stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, coroutines/Flow, Jetpack Compose, Material 3, Navigation Compose |
| Storage | Room (item state), DataStore (settings), MediaStore (public images), app-private files (vault) |
| Background | WorkManager with a content URI trigger |
| Detection | YOLO11n → LiteRT (`com.google.ai.edge.litert`) |
| OCR | ML Kit Text Recognition v2, bundled (`com.google.mlkit:text-recognition`) |
| Image | `Bitmap`/`Canvas`, `androidx.exifinterface` |
| Security | Android Keystore, `javax.crypto` AES-GCM, `androidx.biometric` |
| Image loading | Coil, for public thumbnails only. Vault images are decoded manually into memory. |
| Dependency injection | Manual (`AppContainer`). No Hilt. |
| ML / data (Python) | ultralytics, opencv-python, albumentations, Pillow, Faker (`fil_PH` locale) |
| Validation (Python) | paddleocr, pytesseract, numpy, pandas |
| Annotation | CVAT or Label Studio (only for Roboflow or real-photo additions; synthetic data comes labeled) |
| Version control | Git. Never commit real ID images. |
| License | AGPL-3.0, because of Ultralytics YOLO11 |

Library versions: the latest stable release at project start, pinned in a Gradle version catalog. All tools are free (see §12).

### 8.9 Repository Layout

```
secure-gallery/
  android/
    app/src/main/java/<pkg>/
      scan/       MediaStore query, scan worker, content trigger
      detect/     LiteRT detector, ML Kit OCR, field rules
      redact/     solid-fill renderer, EXIF clean-up, on-device recheck
      vault/      Keystore, DEK wrapping, PIN/biometric/recovery, export/import
      data/       Room entities and DAO (no OCR text)
      ui/         inbox, viewer + eye toggle, vault lock, setup, settings
      AppContainer.kt
    app/src/test/         JVM tests: field rules, confidence levels, state transitions
    app/src/androidTest/  vault round trip, redaction + recheck, airplane-mode flow
  templates/      one JSON per card design variant (normalized field regions),
                  shared by the generator and the app (copied into app assets at build)
  ml/
    generate/     synthetic PH ID/card generator + ground truth (public release)
    augment/      image-quality conditions + layout jitter
    train/        YOLO11n training + LiteRT export
  validation/     desktop harness → metrics CSV + per-condition tables
    leaktest/     leak-test framework (§9.5), run on this app and audited tools
  SECURE_GALLERY.md
```

## 9. Evaluation Plan and Metrics

### 9.1 Metrics

| Area | Metric / method | Operational meaning |
|---|---|---|
| Document detection | Precision, recall, F1, per-class confusion matrix, false-positive rate | How often supported documents are detected and how often negatives are wrongly flagged |
| Localization | IoU and mAP (state the IoU thresholds) | Overlap between predicted and labeled document regions |
| Sensitive-field detection | Field-level precision, recall, F1 | Whether field instances are found. Do not merge OCR correctness and localization into one score. |
| OCR quality | Character error rate or exact field match | Whether OCR reads the known synthetic values |
| Inconclusive results | Count and percentage | How often the system cannot decide whether a field was found |
| Redaction coverage | IoU between redaction and ground-truth field; proportion of field area covered; over-coverage | Whether automatic redactions cover the intended field |
| Localization strategy trade-off | Leak rate vs over-redacted area (% of non-sensitive card area covered), per strategy and condition | Whether layout-first or hybrid beats OCR-first (RQ4) |
| Tool audit | Leak rate per channel (§9.5) per tool, with counts | How much existing tools and this app leave behind (RQ7) |
| User correction rate | Corrections per redacted image, by type (move, resize, add, not sensitive) | How often automation needed human fixing |
| Fallback and retry rate | Whole-document fallbacks and recheck retries, per condition | How often the safety net was needed |
| Residual leakage (desktop) | Leak rate per field, class, and condition (§9.2) | Whether an independent engine can still read redacted fields |
| Redaction-failure detection | Sensitivity, false-negative rate, false-positive rate, inconclusive rate | Whether the on-device check catches the failed-redaction test cases |
| Processing performance | Median and percentiles of latency; scan time; model size; memory | Practicality on the 4 GB RAM device |
| Usability | Task completion, time on task, SUS | Whether users can set up, review, correct, toggle, and export |
| Offline behavior | Full workflow in airplane mode after a clean install | Confirms no network dependency |

Report the numerator and denominator next to every percentage. Use a held-out test set for final metrics, and tune thresholds only on the validation split. Give confidence intervals where feasible, and report results per class and per condition, not only an overall average.

### 9.2 Validation Framework (desktop, offline)

A Python harness in `validation/` runs over the synthetic test set. It uses **PaddleOCR and Tesseract**, so ML Kit is not grading its own work.

- **A field counts as leaked if:** an independent OCR engine reads it back with character error rate ≤ 0.3, **or** any 4 or more consecutive digits of an ID or card number are recovered.
- **Pixel opacity check** on every redaction box, to catch boxes that only look filled.
- **Output:** metrics CSV plus tables per class and per condition.
- The 0.3 character error rate and the 4-digit rule, like the on-device tolerances, are starting values. Calibrate them in a small pilot before reporting.

### 9.3 Experimental Baselines

1. **Detector only:** is a supported document detected and localized?
2. **OCR plus field rules,** without the learned detector.
3. **Localization strategies (RQ4):**
   - **OCR-first:** detector, then OCR and field rules only. This is how existing tools work.
   - **Layout-only:** detector, then template regions only.
   - **Hybrid:** both, unioned. This is the deployed strategy.
4. **Redaction test:** compare automatic regions with ground truth, and run complete, partial, misplaced, and absent redaction cases through both validation levels.

Two detector configurations plus the OCR/rule baseline are enough. Do not compare many models just to make the project look more complex.

### 9.4 Usability Study

- **Participants:** 15–20, using a prepared test phone loaded with synthetic mock IDs, never their own gallery.
- **Tasks:** setup and PIN, scan, review a "needs check" item, fix a box, use the eye toggle, mark a photo "not sensitive," export the vault.
- **Measures:** task completion, time on task, SUS, correction rate.

### 9.5 Leak-Test Framework and Tool Audit

The suite lives in `validation/leaktest/`. It runs offline on redacted outputs exported from any tool.

| Channel | Test | Leak if |
|---|---|---|
| Visible pixels | PaddleOCR + Tesseract on each ground-truth field region | §9.2 rule: character error rate ≤ 0.3 or 4+ consecutive digits recovered |
| Partially covered characters | OCR on a band ±15% around each box, after cropping to the uncovered parts | Clipped tops or bottoms of characters still readable by the §9.2 rule |
| Non-opaque fills | Levels, contrast, and gamma stretch inside the box, then OCR; pixel-variance check | Text recovered, or the fill is not uniform (blur, mosaic, or semi-transparent) |
| Mosaic / blur | Report the redaction style. Recovery attacks are cited (Hill et al. 2016), not reimplemented | Any mosaic or blur used on text is recorded as a leak risk |
| EXIF thumbnail | Extract the embedded thumbnail and run the same OCR on it | The thumbnail shows the unredacted field |
| Metadata | Inspect EXIF/XMP for GPS, device, and original file name | Any GPS or identifying tag left in the file |
| Number structure | For partially hidden card numbers: count the candidates left after Luhn check + issuer prefix | ≤ 1,000 candidates left |
| Leftover copies | After the tool's "save", check MediaStore and the system trash for the unredacted original | The original remains publicly accessible |

**Audit protocol:**
- **Tools:** this app, PrivyMark, Skry, GalleryKeeper, and other comparable Android tools, using the default settings and the same mock-ID set.
- **Recording:** tool version, device, and date for every run.
- **Disclosure:** report results per tool and channel with counts. If a leak is found in a third-party app, notify the developer before publication. Use synthetic IDs only, and check each app's terms first.

## 10. Dataset and Research Ethics

### 10.1 Dataset Plan

- **Benchmark and test set:** a Python generator fills PhilSys, LTO, and payment-card templates with fictional values (Faker `fil_PH` locale, plus number formats that pass validity checks). It writes exact field boxes as ground truth.
- **Conditions:** blur, glare, rotation, low resolution, perspective, compression, crop, and occlusion.
- **Negative examples:** ordinary photos, receipts, screenshots, loyalty cards, blank cards, card-shaped rectangles.
- **Failed-redaction cases:** complete, partial, misplaced, and ineffective redactions.
- **Labels:** class, field type, field region, readability, intended redaction region, and residual detectability.
- **Splits:** all versions of one base image stay in the same split. Split by template and fictional identity, not by random augmented copies.
- **Templates:** one template per card design variant, with normalized field regions. The same template files drive the generator (ground truth) and the app (layout-first localization). For a fair RQ4 test, the test set must include layout jitter: print offset, slight misalignment, and font variation. Otherwise layout-first is graded on its own assumptions.
- **Public release:** generator, templates, and the synthetic dataset, under an open license, with a datasheet (purpose, composition, known gaps). Fictional data only.
- **Detector training only:** synthetic data plus the Roboflow PH ID sets (§19), after a license audit. Roboflow images never enter the test set.
- Treat public datasets as leads. Audit license, provenance, annotation quality, duplicates, and leakage first.

### 10.2 Privacy and Ethics Controls

- No real ID or card numbers in the benchmark.
- The usability study uses a prepared test phone with synthetic images only. If any real photos are ever used, get adviser/ethics approval and informed consent first.
- Never persist OCR text or field values in logs, analytics, crash reports, or the database.
- Mask sensitive values in the interface.
- Delete an original only after the vault copy has been verified and the redacted copy has been saved and re-checked, and only through the Android system delete confirmation.
- Test that exported copies contain opaque pixel fills, not a removable overlay.
- Document limitations clearly. Passing the implemented checks does not mean an image contains no other sensitive information.

## 11. Scope and Delimitations

### In Scope

- Android app (minSdk 30), scanning all permitted gallery images, fully offline.
- PhilSys National ID, LTO driver's license, payment card.
- On-device detection, OCR, field rules, automatic solid-fill redaction, and whole-document fallback.
- Redacted copy shown in the public gallery. Encrypted vault with eye toggle, PIN/biometric/recovery, and export/import.
- Review inbox for correction and "not sensitive" restore.
- On-device recheck and a desktop validation harness.
- Layout-anchored hybrid localization and its comparison with OCR-first and layout-only.
- Leak-test framework and an audit of selected existing Android photo-redaction tools.
- Public release of the synthetic generator, templates, and dataset.
- Quantitative evaluation on a synthetic dataset, plus usability and performance tests on a 4 GB RAM device.

### Stretch Goal (only if the core is on schedule)

- **Purpose-based safe share:** an Android share target. The user picks a purpose (for example "seller verification"), and the app hides only what that purpose doesn't need, stamps "For [purpose] only, [date]," and strips metadata. This follows RA 10173 data minimization. It is not part of the research questions.

### Out of Scope

- Guaranteeing that every kind of sensitive information is detected or removed.
- Deleting originals without the Android system confirmation, or before the vault copy is verified.
- Removing copies already in cloud backups (Google Photos and others). The app warns the user but cannot act on them.
- Cloud upload, server-side OCR, or cloud AI.
- Videos, GIFs, social media, email, cloud drives, web.
- A full gallery replacement (albums, editing, sharing).
- Forensic recovery beyond the §9.5 channels, for example reimplementing mosaic or blur reversal attacks.
- Other Philippine IDs (UMID, passport, and others), non-Latin scripts, iOS.
- Claims of legal compliance merely because the app is offline.
- Long-term behavior-change studies.

---

# Part C. Project Management

## 12. Estimated Cost in Philippine Pesos

This is a planning estimate, not a supplier quotation. It assumes that the team already has a development computer, at least one Android phone, and internet access for research/downloads. Software options are selected to avoid mandatory paid APIs.

| Expense | Low estimate | High estimate | Notes |
|---|---:|---:|---|
| Android development tools, Python, OpenCV, Git | ₱0 | ₱0 | Free tools/open-source libraries; review licenses. |
| On-device OCR/runtime | ₱0 | ₱0 | Assumes a suitable bundled/on-device option with no paid API calls. |
| Annotation and dataset tools | ₱0 | ₱0 | Assumes community/open-source or free-tier tools. |
| Synthetic dataset creation | ₱0 | ₱500 | Possible printing/materials or incidental preparation costs. Fully digital mock data can cost ₱0. |
| Internet/data for research and downloads | ₱300 | ₱1,500 | Depends on existing home/campus access. |
| Transportation / meetings / user testing | ₱300 | ₱1,500 | Depends on number and location of sessions. |
| Printing, consent forms, and proposal documentation | ₱200 | ₱800 | Can be reduced through digital review where allowed. |
| Optional storage/backup media | ₱0 | ₱800 | Only if the team needs a dedicated drive or storage device. Never store real sensitive images unnecessarily. |
| Contingency | ₱200 | ₱700 | For unforeseen project expenses. |
| **Estimated total** | **₱1,000** | **₱5,800** | Excludes purchase of a new computer or Android phone. |

### Cost Risks

- Buying a new phone or laptop would substantially increase the budget and should not be necessary if the team can use existing equipment.
- Cloud OCR or hosted model APIs may introduce usage fees and would conflict with the offline requirement; do not rely on them for the core pipeline.
- GPU cloud rental is optional, not part of the baseline budget. If local training is too slow, first reduce model size, dataset complexity, or experiment count before considering paid compute.
- Check current software and model licenses before distribution or publication.

## 13. BSIT and Institutional Policy Alignment

The NCF *Policies for Capstone Project and Thesis Writing* (CCS Research Manual 2026) describes a BSIT capstone as focusing on the infrastructure, applications, or processes involved in introducing a computing solution to a problem. It identifies IT applications, multimedia systems, and IT management projects as possible forms and states that projects should integrate program knowledge, demonstrate problem-solving and research competence, explore useful applications, and contribute practically to computing or business processes.

This project is framed as an **Applied IT Solution** with a clear **Cybersecurity & Forensics / identity-protection** connection. It involves mobile application development, on-device computer vision, OCR, privacy-conscious software architecture, algorithm evaluation, and usability/performance testing. Its success is not only the existence of an app: the study will report measurable results from a controlled benchmark and tests on an Android device.

### 13.1 SDG Alignment Based on the Attached NCF Manual

| SDG | Relevance to the project | Strength of alignment | How the project will support the alignment |
|---|---|---|---|
| **SDG 16: Peace, Justice and Strong Institutions** | The NCF manual’s BSIT suggested areas explicitly place cybersecurity and forensics, including identity-protection frameworks, under Agenda 7 / SDG 16. | **Primary alignment** | Develop and evaluate an IT application intended to help individuals identify and redact selected sensitive information stored in gallery images. The work relates to privacy and identity protection, while avoiding claims that the app prevents all breaches. |
| **SDG 9: Industry, Innovation and Infrastructure** | The manual lists IT infrastructure and applied IT solutions, and associates some computing and IT-management areas with Agenda 4 / SDG 9. | **Secondary alignment** | Demonstrates a practical mobile computing solution that combines on-device detection, OCR, image processing, and systematic technical evaluation. The alignment is indirect and should be justified through the institution’s research agenda. |
| **SDG 4: Quality Education** | The manual maps some BSIT applied IT solution examples, such as e-learning management systems, to SDG 4. | **Not a primary alignment** | The application is not an education system. Mention SDG 4 only if the adviser or NCF research agenda provides a specific connection, such as an educational privacy-literacy component. Otherwise, do not claim it as a main SDG. |

**Recommended SDG statement:** The project primarily aligns with SDG 16 through the development and evaluation of a privacy-oriented identity-protection application, with a secondary connection to SDG 9 through applied IT innovation. This mapping follows the BSIT cybersecurity and applied-IT categories in the attached NCF manual. The final proposal should cross-check the exact SDG agenda wording in the manual’s research-agenda appendix and obtain adviser approval. The project does not directly measure progress toward an SDG target; it contributes a small technical application relevant to the stated themes.

## 14. Expected Outputs

1. A working Android app that performs the agreed workflow offline.
2. A publicly released synthetic PH ID generator, templates, dataset, and datasheet.
3. Held-out detection and field-level metrics for the detector configurations and the OCR/rule baseline.
4. The localization-strategy comparison (OCR-first vs layout-only vs hybrid) by condition.
5. Redaction coverage, correction-rate, fallback/retry, and residual-leakage results from both validation levels.
6. The leak-test framework and an audit report of existing Android photo-redaction tools.
7. Runtime and usability results from the 4 GB RAM test device.
8. System documentation, privacy/data-handling notes, test cases, and a final technical report.

## 15. Risks and Mitigation

| Risk | Mitigation |
|---|---|
| Document classes too broad | Three classes only. Expand only after the pilot proves feasible. |
| Existing apps offer similar features | Hands-on comparison (§16.2). Base the contribution on the measured combination, not on feature novelty alone. |
| Small or biased dataset | Synthetic templates, diverse layouts, controlled conditions, splits by template. State limitations honestly. |
| Detector fails on real photos | Test each condition separately. The whole-document fallback covers unreadable cases. |
| OCR misses fields | Medium-confidence detections go to "needs check." Report inconclusive cases. |
| Original lost through a bug | Write-and-verify the vault copy before any delete. Never overwrite the original. Instrumented tests of the full round trip. |
| Vault keys lost (forgotten PIN, uninstall) | Biometric and recovery-code unlock. Vault export. Explicit warnings. |
| Cloud backup still holds the original | Disclose it in the app and in the delimitations. |
| Redaction only overlays the image | Flattened JPEG. Pixel check on the device and on the desktop. |
| Offline claim inaccurate | Remove `INTERNET` from the merged manifest. Airplane-mode test after a clean install. |
| Real personal data retained | Synthetic data only. No OCR text in storage or logs. |
| Too slow on a 4 GB RAM phone | Downsampled decoding, one image at a time, INT8 model, background work. |
| Card design variants break the templates (PhilSys card vs printed ePhilID, old vs new LTO designs) | One template per variant, chosen by the detector class or a small variant classifier. Out-of-template designs fall back to OCR-first, then whole-document fill. |
| Layout-first wins only because the test data matches the templates exactly | Layout jitter in the generator. Report results on a held-out set of real-design mock prints photographed with a phone. |
| Audited apps change or forbid testing | Record versions and dates. Check terms. Notify developers before publishing. Use synthetic IDs only. |
| Workload too large | No full gallery, no on-device language models, no behavior study. Purpose-based safe share is a stretch goal only. |

---

# Part D. Sources

## 16. Related Systems (RRS)

Product claims come from developer pages, store listings, and repositories. They are not independent verification. "Not established" means the reviewed source did not document the feature; it does not mean the feature is absent. Record the access date and version for each system. Systems are not counted toward the 90 RRL citations.

### 16.1 Comparison Matrix

| # | System | Type | Gallery-wide scan | Offline claim | OCR / field-level | Redaction / review | Evidence limit or team observation | How this project differs | Link |
|---|---|---|---|---|---|---|---|---|---|
| 1 | GalleryKeeper | Android app, open source | Reported | Reported (local analysis, no photo saving) | Field-level OCR not established | Not established; check current app | YOLO11 classification of IDs, passports, cards. **Team test:** did not find the expected Philippine IDs and appeared to sort images into folders (one observation; repeat with controlled images). | PH-specific classes, field-level redaction, vault, validated output | https://github.com/chabanenary/GalleryKeeper-App |
| 2 | Skry | Android app | Reported | Reported; listing indicates no Internet permission | On-device OCR + rules (IDs, cards, phones, emails, GPS EXIF) | Audit workflow; pixel redaction not established | Very close comparator. Do not claim that gallery scan + OCR rules is novel. Not directly tested. | Automatic pixel redaction that replaces the public copy, plus residual checks | https://play.google.com/store/apps/details?id=com.yishenghuang.skry |
| 3 | PrivyMark | Android app | Not established | On-device reported | Faces, phone numbers, emails, links, ID-like text | Manual review; solid, mosaic, blur; exports a copy, keeps the original | **Team test:** missed blurred or unclear text, and some regions were inaccurate. Repeat and record version, device, and condition. | Gallery-wide automation, whole-document fallback for blur, residual checks | https://play.google.com/store/apps/details?id=com.candlestroke.picture.mark |
| 4 | Perception Privacy Mobile App | Mobile app, open source | Gallery selection; auto-scan not established | Partly; text PII uses a ChatGPT API | ML Kit OCR + API-based PII | Interactive masking and export | Not fully offline as documented | Fully offline pipeline | https://github.com/Chainathan/Perception-Privacy-Mobile-App |
| 5 | Privacy-Preserving Image Filtering | Training repository | n/a | n/a | OCR / PII text experiments | n/a | Segmentation for ID cards, screens, QR codes, plates. Not a deployed tool. | Technical reference only | https://github.com/Chainathan/Privacy-Preserving-Image-Filtering-DL |
| 6 | PrivLens | Android research prototype | Capture-time monitoring | On-device reported | OCR fusion exploratory | Region blur, capture warnings | Its categories and results don't transfer to PH IDs. Discusses domain shift. | PH document classes, solid fill, gallery-wide | https://github.com/axsmilex/PrivLens |
| 7 | Private Gallery | Android app | Advertised | On-device AI advertised | Not ID-field focused | Hide/delete flagged files | Different content focus (inappropriate images). Not directly tested. | Field-level redaction of IDs/cards | https://play.google.com/store/apps/details?id=com.privategallery.private_gallery |
| 8 | Facet | Photo app | Advertised | Local analysis advertised | Unknown | Separates sensitive photos; review of uncertain results | Supported classes unknown. Not directly tested. | Defined PH classes, redaction, measured benchmark | https://facetapp.io/home |
| 9 | RollGuard | iOS app | Advertised | On-device advertised | ID/card/credential categories; field OCR unclear | Not established | Site warns that blurred, cropped, or dim IDs may be missed (capture page and date). Not directly tested. | Android, whole-document fallback for poor images | https://rollguard.app/ |
| 10 | Google Photos AI search | Commercial app | General library | Not established | AI answer listed images with IDs, invoices, a card | Said it could not redact in that interaction | Single team screenshot; avoid broad claims | Explicit redaction workflow, offline | (screenshot in project) |
| 11 | Lumen Gallery | Open-source gallery | Gallery app | Local/offline features | Offline OCR text search, semantic labels | Not established | Not a documented ID redactor | Privacy-specific detection and redaction | https://github.com/Heitzlni/lumen-gallery |
| 12 | Manual search + image editor | Existing workflow | Manual | Depends on tools | User reads it | User draws covers | Slow; depends on the user noticing every field. Support with usability literature. | Automates finding and covering | (no single source) |
| 13 | General OCR / scanner apps | Software category | No | Varies | Text extraction | Not a primary function | Pick named tools if comparing products | Privacy-specific fields, redaction, validation | (category) |

### 16.2 Comparator Testing Log

For each system, record:

- App/repository version and access date
- Device and Android/iOS version
- Test image ID (use mock documents)
- Image condition: clear, mild blur, moderate blur, glare, rotation, low resolution, crop/occlusion
- Whether the app scanned the image or gallery
- Whether it identified the correct document category
- Whether it found the selected sensitive field
- Whether the proposed redaction region covered the entire target field
- Whether manual correction was available
- Whether the exported image was a separate copy and the original was preserved
- Whether selected information remained detectable in the exported copy (run the §9.5 leak-test suite on the export, all channels)
- Result: Pass / Fail / Partial / Not tested
- Screenshot or evidence note

## 17. Related Literature (RRL)

Three themes with 30 citations each, all published 2021–2026 (90 in total). Sources were found with SciSpace and Consensus. Links go to the DOI or the Consensus record. Summaries are written from the retrieved abstracts or metadata. A dagger (†) after a title means the summary rests on limited metadata, so read the full paper before citing any detail. Older foundational works and other leads are in §18 and do not count toward the 90.

| Theme | Focus | Citations |
|---|---|---|
| Theme 1 | Visual privacy detection, redaction and obfuscation | 30 |
| Theme 2 | ID and card detection, OCR and field extraction under image-quality conditions | 30 |
| Theme 3 | On-device deployment, PII handling, privacy by design and usability | 30 |

### 17.1 How the Themes Map to the System

|System step|Theme|
|---|---|
|Find sensitive images and regions in the gallery|Theme 1 and Theme 2|
|Read the text and find the sensitive fields|Theme 2|
|Redact with solid fill and check what remains|Theme 1|
|Run offline on the phone, handle PII lawfully, let the user correct|Theme 3|

### 17.2 Theme 1: Visual privacy detection, redaction and obfuscation

|#|Title|Authors|Year|Venue|Abstract summary|Relevance to the Gallery Privacy Reviewer|Link|
|---|---|---|---|---|---|---|---|
|1|Automated Privacy Protection for Mobile Device Users and Bystanders in Public Spaces|Darling|2021|Dissertation, University of Arkansas|Develops photo bystander anonymization with a CNN face classifier and detection of sensitive content in user interfaces.|Shows an early mobile pipeline that detects sensitive regions and anonymizes them, which mirrors the detect-then-redact flow of your app.|[Link](https://consensus.app/papers/details/0c27a00f228456689a19d9078e5e9e0e/)|
|2|Exploring Safer Image Sharing: A Vision-Language Approach to Privacy Risk Detection and Protection (IPPA)|Tran et al.|2025|IEEE MASS|Combines vision-language keywords with a fine-tuned classifier to predict image privacy risk, then applies watermark-based removal with reconstruction.|Supports risk scoring before sharing and removal of sensitive content, and shows the cost of heavy models for a phone.|[Link](https://consensus.app/papers/details/842efcf3bf615a939b6971fede1047db/)|
|3|PrivAttNet: Predicting Privacy Risks in Images Using Visual Attention|Chen et al.|2021|ICPR (2020 proceedings)|Uses visual attention to score how privacy-sensitive an image is.|Gives a baseline for whole-image sensitivity scoring that your region-level detector goes beyond.|[Link](https://consensus.app/papers/details/196c34b68cd6542b9ed09ef450721a5d/)|
|4|Beyond Visual Appearances: Privacy-sensitive Objects Identification via Hybrid Graph Reasoning (PrivacyGuard)|Jiang et al.|2024|arXiv|Finds privacy-sensitive object boxes by reasoning over scene context in a graph.|Shows that context helps decide which detected objects are private, which matters when an ID appears next to non-sensitive items.|[Link](https://consensus.app/papers/details/4e7a459144c959ce9bb899ef9fb8fe3a/)|
|5|VPD-100K: Towards Generalizable and Fine-grained Visual Privacy Protection|Hu et al.|2026|PMLR vol. 306|Introduces about 100,000 images with 33 classes, including on-screen PII and physical identifiers.|Closest large taxonomy for visual PII classes and a warning that transfer to Philippine IDs needs checking.|[Link](https://consensus.app/papers/details/328c1b471de05dfd8d2c149b260823bf/)|
|6|TSLEPS: Two-Stage Private Text Localization and Erasure|Li et al.|2025|Sensors|Two-stage method that finds private text and erases it, deployable on mobile devices, reporting 97.5% accuracy and 96.4% recall.|Shows private-text detection plus removal that fits on a phone, with accuracy figures to compare.|[Link](https://doi.org/10.3390/s25165162)|
|7|PrivPAS: Real-time Lightweight Sensitive Content Detection|H.S. et al.|2022|IEEE ICSC|Real-time lightweight sensitive-content detector with an 8.49 MB model and mAP of 89.52%.|Evidence that a small detector suits phone deployment, with size and accuracy figures you can compare against.|[Link](https://consensus.app/papers/details/9b1b07eeb90f5eaba13c7293864d284b/)|
|8|Automated Intelligent Redaction System for Personally Identifiable Information in Identity Card Screenshots|Mounika et al.|2025|IEEE ICSCSA|Combines deep-learning object detection with OCR to mask ID numbers, names, birth dates and photos, reporting 95.6% detection accuracy in under one second per image.|Nearest match to your core pipeline (detect, OCR, mask) on identity images.|[Link](https://doi.org/10.1109/icscsa66339.2025.11170735)|
|9|REDACT: PII Redaction and Privacy Protection with Ollama Integration|Aasif J et al.|2025|IEEE ICCMC|Pairs OCR with a locally run language model to redact PII and lets users edit the rules.|Shows local-only redaction with user-editable rules, relevant to your offline and user-control goals.|[Link](https://doi.org/10.1109/iccmc65190.2025.11140717)|
|10|Automated Redaction of PII on Drug Labels Using OCR and LLMs|Thetbanthad et al.|2025|Applied Sciences|Uses EasyOCR, Tesseract and a Qwen model under Thailand's PDPA, reporting precision 92.5, recall 83.2 and F1 87.6.|A regional-law redaction study with measured precision and recall, similar to your RA 10173 framing.|[Link](https://doi.org/10.3390/app15094923)|
|11|DICOM De-Identification via Hybrid AI and Rule-Based Framework|Rasool, Bouaynaya|2025|arXiv|Combines rules, an uncertainty-aware Faster R-CNN, OCR and NER to remove PHI and PII from metadata and pixels, with human review of uncertain cases.|Supports the hybrid rule plus model design and human-in-the-loop checks in your confirmation step.|[Link](https://doi.org/10.48550/arxiv.2507.23736)|
|12|Protecting Privacy with Image Processing: How Blurring and Masking Help|Ahmed, Baroud|2022|J. Res. Appl. Sci. Biotechnol.|Compares blur, pixelation and masking and finds masking protects best.|Backs your choice of solid-fill redaction over blur.|[Link](https://doi.org/10.55544/jrasb.1.1.18)|
|13|Comparative Evaluation of Visual Obfuscation Methods for Protecting Private Information in Screen Sharing|Makita et al.|2025|ACM|User study with 55 participants where background-color masking gave the best balance of protection and usability.|Direct evidence for solid-color masking and a model for your own user evaluation.|[Link](https://doi.org/10.1145/3737821.3749557)|
|14|A Framework for Evaluating Image Obfuscation under Deep Learning-Assisted Privacy Attacks|Jebb|2023|Multimedia Tools and Applications|Defines an adversary model with restoration and recognition attacks against blur, pixelation and masking.|Gives the method to test residual information after redaction, your post-redaction check.|[Link](https://doi.org/10.1007/s11042-023-14664-y)|
|15|DeepFakes for Privacy: Investigating the Effectiveness of State-of-the-Art Privacy-Enhancing Face Obfuscation Methods|Khamis et al.|2022|AVI|Survey of 110 people on how well obfuscation methods hide identity.|Shows that obfuscation effectiveness depends on viewers, so you should test with people and with tools.|[Link](https://doi.org/10.1145/3531073.3531125)|
|16|HideSeeker: Uncover Privacy Risk Behind On-Device Obfuscation|Liu et al.|2022|ACM SenSys|Shows that content can be re-disclosed after on-device obfuscation.|A warning that obfuscation can leak, which supports verifying output after redaction.|[Link](https://doi.org/10.1145/3560905.3568514)|
|17|WebPII: Benchmarking Visual PII Detection for Computer-Use Agents|Zhao|2026|arXiv|Builds 44,865 synthetic UI images for PII detection and trains a detector that runs in 20 ms on a CPU.|Shows synthetic data can train fast visual PII detectors, a model for your mock-ID dataset.|[Link](https://doi.org/10.48550/arxiv.2603.17357)|
|18|Evaluation of Human Visual Privacy Protection (HR-VISPR)|Abdulaziz et al.|2025|arXiv|Scores 11 protection methods on privacy, utility and practicality.|Provides a three-part evaluation frame you can reuse for redaction coverage, utility and speed.|[Link](https://doi.org/10.48550/arxiv.2507.13981)|
|19|DIPA: Dataset of Image Privacy Annotations|Xu et al.|2023|IUI|1,495 images with 5,671 annotations that capture cross-cultural views of what is private.|Shows that privacy judgments differ by culture, which supports Philippine-specific classes.|[Link](https://doi.org/10.1145/3581754.3584176)|
|20|DIPA2: Object-Level Image Privacy Dataset|Xu et al.|2023|IMWUT|Object-level privacy annotations on 1,304 images from Japan and the UK.|Shows object-level labels that match your region-level approach.|[Link](https://doi.org/10.1145/3631439)|
|21|PrivacyAlert: A Dataset for Image Privacy Prediction|Zhao et al.|2022|ICWSM|Flickr dataset labeled private or public.|Reference dataset for image-level privacy classification.|[Link](https://doi.org/10.1609/icwsm.v16i1.19387)|
|22|Image Privacy Protection: A Survey|Wen et al.|2024|arXiv|Systematic review of protection at the data, content and feature levels.|Gives the map of the field for your literature chapter.|[Link](https://doi.org/10.48550/arxiv.2412.15228)|
|23|PrivEdit|Chen et al.|2025|ACM Multimedia (demo)|Uses natural-language prompts to detect items including ID cards, builds masks with SAM and applies blur or mosaic in an interactive editor.|Shows interactive review of detected ID regions, close to your confirm step.|[Link](https://doi.org/10.1145/3746027.3754466)|
|24|Designing Accessible Obfuscation Support for Blind Individuals' Visual Privacy Management|Zhang et al.|2024|CHI|Study with 12 blind participants on AI obfuscation errors and cognitive load.|Shows how detector errors burden users, which supports a clear review interface.|[Link](https://doi.org/10.1145/3613904.3642713)|
|25|PrivateEdit|Tamboli et al.|2026|IEEE Transactions on Artificial Intelligence|On-device segmentation masking with a tunable mask interface.|On-device masking with user control, similar to your app.|[Link](https://doi.org/10.1109/tai.2026.3671211)|
|26|Imago Obscura: An Image Privacy AI Co-pilot|Monteiro et al.|2025|arXiv|Study with 15 users of an intent-aware copilot that helps edit images for privacy.|Evidence that users want help but also control over edits.|[Link](https://doi.org/10.48550/arxiv.2505.20916)|
|27|Towards Automated Content-based Photo Privacy Control in User-Centered Social Networks (AutoPri)|Vishwamitra et al.|2022|CODASPY|Detects user-specific private photos with 94.32% accuracy and localizes the sensitive regions.|Pairs photo-level detection with region localization, the same two steps in your design.|[Link](https://doi.org/10.1145/3508398.3511517)|
|28|Stroke-Based Scene Text Erasing Using Synthetic Data for Training|Tang et al.|2021|IEEE Transactions on Image Processing|Erases text by predicting a stroke mask and inpainting.|Gives the erasure alternative to solid fill and shows what a mask must cover.|[Link](https://doi.org/10.1109/TIP.2021.3125260)|
|29|DiffSTR: Controlled Diffusion Models for Scene Text Removal|Pathak et al.|2024|arXiv|Removes scene text with diffusion models for privacy uses.|Shows heavier generative erasure that is likely too costly for an offline phone app.|[Link](https://doi.org/10.48550/arxiv.2410.21721)|
|30|The Surprisingly Straightforward Scene Text Removal Method with Gated Attention and Region of Interest Generation (GaRNet)|Lee, Choi|2022|ECCV|Removes text with gated attention and conceals IDs, phone numbers and plates.|Names IDs and phone numbers as removal targets, which matches your use case.|[Link](https://doi.org/10.48550/arXiv.2210.07489)|

#### Theme 1 gaps

1. Most privacy datasets use general classes such as faces, screens and documents. None of the 30 studies covers Philippine IDs or Philippine payment cards.
2. Redaction studies measure whether a person or a model can still identify content (Jebb 2023, Khamis 2022, Makita 2025), but none runs an automatic check on the exported phone image for the exact fields that were meant to be hidden.
3. Heavy methods such as diffusion erasure (DiffSTR) and vision-language models (IPPA) do not fit a fully offline phone app, and few papers report phone speed or memory.
4. Studies of interactive review (PrivEdit, Imago Obscura, Zhang 2024) show users want control, yet none reports how the reviewer step changes final redaction coverage.

### 17.3 Theme 2: ID and card detection, OCR and field extraction under image-quality conditions

|#|Title|Authors|Year|Venue|Abstract summary|Relevance to the Gallery Privacy Reviewer|Link|
|---|---|---|---|---|---|---|---|
|31|Image Detection and Data Extraction Using Hybrid Deep Learning (ID cards)|Rao et al.|2025|Research Square (preprint)|Combines Mask R-CNN, CRNN and PyTesseract to find ID cards and extract their data.|A direct detection plus OCR pipeline for ID cards.|[Link](https://doi.org/10.21203/rs.3.rs-7065509/v1)|
|32|MobileMRZNet|Bayar et al.|2024|Balkan J. Electrical and Computer Engineering|A 400 KB machine-readable-zone detector that runs in under 10 ms on mobile.|Proof that a tiny detector for ID parts can run on a phone.|[Link](https://doi.org/10.17694/bajece.1416404)|
|33|A Custom-Built Deep Learning Approach for Text Extraction from Identity Card Images|Suddul, Seguin|2024|IJ-ICT|Uses U-Net text detection and a CRNN on Mauritius ID cards, reaching IoU 0.70 and about 97% word accuracy.|Gives per-stage numbers for text localization and recognition on an ID.|[Link](https://doi.org/10.11591/ijict.v13i1.pp34-41)|
|34|Text Region Localization and Recognition for ID Card Identification using Deep Learning|Maung, Aye|2023|ICCA|Faster R-CNN for text regions followed by a recurrent recognizer on ID cards.|Another field-localization baseline for ID text.|[Link](https://doi.org/10.1109/ICCA51723.2023.10182194)|
|35|An End-to-End Deep Learning Architecture for Information Extraction from Turkish Identity Cards|Eskicioglu, Isik|2025|Advances in Artificial Intelligence Research|YOLOv8m segmentation reaching 99.5 mAP, perspective correction and TrOCR, with 92.6% overall extraction accuracy.|Shows the value of perspective correction before OCR on ID photos.|[Link](https://doi.org/10.54569/aair.1845016)|
|36|FGW-VnText: Vietnamese ID Text Extraction|Nguyen et al.|2023|ACM|YOLOv5, CRAFT and VietOCR in sequence, with exact-match rates of 90.57% to 94.64%.|Local-language ID extraction with a staged pipeline.|[Link](https://doi.org/10.1145/3591569.3591596)|
|37|Mero Nagarikta: Nepali Citizenship Card Extraction|Dhakal et al.|2024|arXiv|YOLOv8 with tuned PyTesseract in a mobile app, with mAP of 99.1 and 96.1.|Close to your stack: YOLOv8 plus Tesseract for a national ID in a mobile app.|[Link](https://doi.org/10.48550/arxiv.2410.05721)|
|38|Automating ID Card Verification Leveraging Deep Learning †|(Author not recorded; TU Wien thesis)|2025|TU Wien|YOLOv8 segmentation near 90%, ResNet with angular margin at 98.07%, PaddleOCR at 93.35% of fields, and Llama 3.2 Vision at 96.72%.|Compares OCR engines and a vision-language model on the same IDs.|[Link](https://doi.org/10.34726/hss.2025.123675)|
|39|Penerapan metode OCR untuk mengidentifikasi teks pada SIM|Darmi et al.|2025|JATI|OCR on driver licenses reaching up to 95%, with image quality shaping results.|Shows image quality drives OCR accuracy on licenses.|[Link](https://doi.org/10.36040/jati.v9i4.13987)|
|40|Optimization of Tesseract OCR for Indonesian ID Cards (KTP) via Preprocessing|Ramadhan et al.|2025|IJSECS|Grayscale, denoising, CLAHE and thresholding raised Tesseract accuracy from 39.55% to 64.97%.|Quantifies preprocessing gains on a national ID, useful for your pipeline.|[Link](https://doi.org/10.35870/ijsecs.v5i3.5183)|
|41|Sistem Pengenalan Citra Dokumen Teks Terdistorsi (deblurring with DnCNN)|Zalukhu et al.|2025|JTIK|DnCNN deblurring on 300 images, with PSNR 29.56 and SSIM 0.89.|Speaks to blurred input, a condition you plan to test.|[Link](https://doi.org/10.35870/jtik.v10i1.4700)|
|42|DeepScan: Credit Card Number Recognition Using Deep BiLSTM|Huang, Chuah|2025|IEEE ICHCI|CTPN text detection with BiLSTM recognition of bank card numbers.|Targets payment card numbers, one of your two document families.|[Link](https://doi.org/10.1109/ichci67614.2025.11292320)|
|43|Research on Bank Card Number Recognition Method Based on Template Matching|Wu, Xu|2023|IEEE ICBDA|OpenCV template matching reaching 95.6% on card numbers.|A lightweight non-neural option for card digits.|[Link](https://doi.org/10.1109/ICBDA57405.2023.10104716)|
|44|MIDV-2020: A Comprehensive Benchmark Dataset for Identity Document Analysis|Bulatov et al.|2021|arXiv / Computer Optics 2022|1,000 mock identity documents with 72,409 annotated images and Tesseract baselines.|Model for a mock-document dataset that avoids real personal data.|[Link](https://doi.org/10.48550/arxiv.2107.00396)|
|45|Document Liveness Challenge Dataset (DLC-2021)|Polevoy et al.|2022|Journal of Imaging|1,424 clips covering glare and recapture, built from synthetic documents to stay GDPR compliant.|Covers glare and recapture, two image conditions you plan to vary.|[Link](https://doi.org/10.3390/jimaging8070181)|
|46|Exploring OCR: Combining Open-Source Engines|Pandelica et al.|2025|RoEduNet|Combines Tesseract and EasyOCR to read faded text under uneven light.|Shows engine ensembles help on poor lighting.|[Link](https://doi.org/10.1109/roedunet68395.2025.11208270)|
|47|Benchmarking OCR Tools for Historical Postcards|Tabbone, Pelingre|2025|ACM|Tests EasyOCR, Tesseract, docTR, PaddleOCR and Calamari on 4,293 postcards.|A benchmark design you can copy for choosing an OCR engine.|[Link](https://doi.org/10.1145/3746273.3760201)|
|48|Evaluating License Plate Recognition in Indonesia: A Comparison of OCRs|Thenisius et al.|2025|IEEE ICIC|Tests EasyOCR, PaddleOCR and Tesseract on 2,167 plates under six degradations such as fog, noise, low light, blur and rain.|Shows a clean way to report OCR accuracy by degradation type.|[Link](https://doi.org/10.1109/icic68054.2025.11308159)|
|49|Perbandingan akurasi Tesseract dan EasyOCR sebelum dan sesudah prapemrosesan pada citra nota|Puteri et al.|2026|JITET|Compares Tesseract and EasyOCR on receipts with character error rates of 18% to 25% and checks the effect of preprocessing.|More evidence on preprocessing effects across two open OCR engines.|[Link](https://doi.org/10.23960/jitet.v14i1.8603)|
|50|Avaliacao de OCR Embarcado para Dispositivos Moveis|Fernandes et al.|2025|SIBGRAPI|Compares Vision OCR, ML Kit and Tesseract Mobile on 848 samples, noting privacy from on-device use and usability.|Best match for choosing an Android OCR engine for your app.|[Link](https://doi.org/10.5753/sibgrapi.est.2025.38292)|
|51|Breaking Barriers in Text Analysis (lightweight OCR evaluation)|Karanth et al.|2023|ICACRS|Evaluates Tesseract, Keras OCR, EasyOCR and PaddleOCR.|Adds a four-engine comparison.|[Link](https://doi.org/10.1109/icacrs58579.2023.10404305)|
|52|The Efficacy of Tesseract OCR †|(Authors not recorded)|2024|Conference proceedings|Practical evaluation of Tesseract accuracy.|Adds a Tesseract-focused accuracy reference.|[Link](https://doi.org/10.62919/hdsg3874)|
|53|BROS: A Pre-trained Language Model Focusing on Text and Layout for Better Key Information Extraction|Hong et al.|2022|AAAI|Uses text plus layout to extract key fields on FUNSD, SROIE and CORD.|Shows how layout helps assign meaning to OCR text, which your field rules replace with simpler patterns.|[Link](https://doi.org/10.1609/aaai.v36i10.21322)|
|54|PICK: Processing Key Information Extraction from Documents using Improved Graph Learning-Convolutional Networks|Yu et al.|2021|ICPR|Graph learning for key information extraction.|Another structured extraction approach for comparison.|[Link](https://doi.org/10.1109/ICPR48806.2021.9412927)|
|55|EIGEN: Expert-Informed Joint Learning Aggregation for High-Fidelity Information Extraction from Document Images|Singh et al.|2023|arXiv|Uses rules and deep models to create weak labels for extraction.|Supports mixing rules with models, as in your regex plus OCR plan.|[Link](https://doi.org/10.48550/arxiv.2311.13993)|
|56|Enhancing Document Information Analysis with Multi-Task Pre-training|Ali, Roy|2023|IJCNN 2024 / arXiv|Multi-task layout model tested on FUNSD, CORD and SROIE.|Context for layout-aware extraction.|[Link](https://doi.org/10.48550/arxiv.2310.16527)|
|57|IDNet: A Novel Identity Document Dataset via Few-Shot and Quality-Driven Synthetic Data Generation|Xie et al.|2024|IEEE Big Data|837,060 synthetic identity document images from 20 types, built with generative models and Bayesian optimization.|Large synthetic ID set that shows privacy-safe data generation.|[Link](https://doi.org/10.1109/bigdata62323.2024.10825017)|
|58|Automated Detection of Identification Documents with Open Source Face Detection and Text Recognition Libraries|Colón-Santiago et al.|2025|IEEE CARS|Finds IDs inside file systems using open-source face detection and OCR, with up to 97% accuracy and no false positives on over 2,000 files.|Closest to scanning a store of files for IDs, which is your gallery scan.|[Link](https://doi.org/10.1109/cars67163.2025.11337447)|
|59|Fast End-to-End Deep Learning Identity Document Detection, Classification and Cropping|Chiron et al.|2021|ICDAR|Modular deep-learning pipeline that classifies the document and estimates its quadrilateral for alignment.|Shows document localization and alignment under varied capture conditions.|[Link](https://doi.org/10.1007/978-3-030-86337-1_23)|
|60|IDTrust: Deep Identity Document Quality Detection with Bandpass Filtering|Al-Ghadi et al.|2024|arXiv|Deep model that judges ID image quality on MIDV-2020 and L3i-ID without alignment steps.|Image-quality checks that could gate low-quality inputs.|[Link](https://doi.org/10.48550/arxiv.2403.00573)|

#### Theme 2 gaps

1. ID extraction papers cover Mauritian, Turkish, Vietnamese, Nepali, Indonesian and other IDs. None covers the Philippine IDs in your scope (for example PhilSys, driver license, UMID, passport).
2. Most results come from clean or scanner-like images. Few report accuracy by image condition (blur, glare, rotation, low resolution), and only a handful of OCR studies (Thenisius 2025, Ramadhan 2025) separate results by degradation.
3. Payment card work focuses on the card number only. Name, expiry date and security code fields get little attention, although all are sensitive.
4. OCR engine comparisons rarely use the same Android runtime you will ship. Only Fernandes 2025 compares engines built for mobile.
5. Public datasets (MIDV-2020, IDNet, DLC-2021) use foreign mock documents, so a Philippine mock dataset is still needed.

### 17.4 Theme 3: On-device deployment, PII handling, privacy by design and usability

|#|Title|Authors|Year|Venue|Abstract summary|Relevance to the Gallery Privacy Reviewer|Link|
|---|---|---|---|---|---|---|---|
|61|Design and Implementation of MobileNet-Based YOLO for Resource-Constrained Devices †|Hong et al.|2025|IEEE ICTC|A YOLO design built on a MobileNet backbone for devices with limited resources.|Supports your lightweight detector choice. (Summary based on title and metadata.)|[Link](https://doi.org/10.1109/ictc66702.2025.11387904)|
|62|Lightweight DB-YOLO Facemask Detection and Android App|Qin et al.|2023|Electronics|Lightweight YOLO deployed on Android with NCNN at 33 FPS.|Shows a working YOLO to Android path with measured speed.|[Link](https://doi.org/10.3390/electronics12244936)|
|63|Nav-YOLO|Su et al.|2025|ISPRS International Journal of Geo-Information|A lightweight YOLOv8n model packaged in an Android app.|YOLOv8n on Android, the same model family you plan to use.|[Link](https://doi.org/10.3390/ijgi14090364)|
|64|Lightweight Object Detection Using YOLOv7 with ShuffleNetV2 and ViT †|Gong|2024|arXiv|Reduces YOLOv7 with ShuffleNetV2 and a vision transformer block.|Options for shrinking detectors. (Summary based on title and metadata.)|[Link](https://doi.org/10.48550/arxiv.2403.01736)|
|65|LW-YOLO †|Chen et al.|2022|ACM AIPR|Lightweight YOLO variant for efficient detection.|Another size-versus-accuracy reference. (Summary based on title and metadata.)|[Link](https://doi.org/10.1145/3573942.3574027)|
|66|FedEdgeDetect †|Yan|2025|Internet Technology Letters|Federated learning for object detection at the edge.|Shows training without moving raw images off devices, a future direction for privacy. (Summary based on title and metadata.)|[Link](https://doi.org/10.1002/itl2.70145)|
|67|DLNet †|Kang et al.|2022|ICOIN|Lightweight detection network for constrained hardware.|Adds a compact network option. (Summary based on title and metadata.)|[Link](https://doi.org/10.1109/ICOIN53446.2022.9687252)|
|68|A Study of User Concerns about Smartphone Privacy|Fung et al.|2022|CSNet|Survey of 1,000 users that shows concern about privacy alongside weak protective behavior.|Supports the need for a tool that makes protection easy.|[Link](https://doi.org/10.1109/CSNet56116.2022.9955623)|
|69|Toward a Secure Future: An Integrated Framework for PII Detection Using NLP and Visual Analysis|Sheela et al.|2026|IEEE Access|Hybrid system with regex, spaCy, BERT and a Swin-based Faster R-CNN; regex F1 0.445, hybrid text F1 0.515 and the vision model F1 0.978 on a synthetic Indian PII set.|Shows vision detection beating text rules on documents, and uses a synthetic regional dataset.|[Link](https://doi.org/10.1109/access.2026.3669713)|
|70|A Hybrid AI-based and Rule-based Approach to DICOM De-identification: A Solution for the MIDI-B Challenge|Dvoretskii et al.|2025|arXiv|Uses rules for structured data, PaddleOCR and RoBERTa for free text, reaching 99.91% accuracy on the test set.|Shows rules for structured fields and a model for free text, like your regex plus OCR design.|[Link](https://doi.org/10.48550/arxiv.2509.00437)|
|71|An Evaluation Study of Hybrid Methods for Multilingual PII Detection (RECAP)|Asif|2025|arXiv|Joins regular expressions with language models across 13 low-resource locales and beats fine-tuned NER by 82% in weighted F1.|Supports rule patterns for local formats such as Philippine ID numbers.|[Link](https://doi.org/10.48550/arxiv.2510.07551)|
|72|Privacy-Centric AI Systems for Identifying and Securing Sensitive Information|Kodakandla|2025|World Journal of Advanced Engineering Technology and Sciences|Architecture for finding and masking PII with 94% to 97% accuracy under 120 ms.|Context for fast on-site masking and compliance goals.|[Link](https://doi.org/10.30574/wjaets.2025.14.3.0126)|
|73|Detecting Sensitive Information from Documents|Patel, Verma|2025|IEEE ICIRCA|Fine-tunes BERT for named entity recognition of sensitive data and compares it with rule-based methods.|Compares rules and learned models for sensitive fields.|[Link](https://doi.org/10.1109/icirca65293.2025.11089654)|
|74|On-Device AI for Privacy-Preserving Mobile Applications: A Framework using TensorFlow Lite|Misra|2025|International Journal for Science Technology and Engineering|Framework using quantization and pruning for TensorFlow Lite models, with up to 35% faster response than cloud and no user data leaving the phone.|Supports offline inference as a privacy choice and gives optimization steps.|[Link](https://doi.org/10.22214/ijraset.2025.73873)|
|75|Breaking SafetyCore: Exploring the Risks of On-Device AI Deployment|Guyomard|2025|arXiv|Shows how the model inside Android's sensitive image detection service can be extracted and bypassed.|A risk note: on-device models can be attacked, so plan for model protection.|[Link](https://doi.org/10.48550/arxiv.2509.06371)|
|76|MMGuard: Automatically Protecting On-Device Deep Learning Models in Android Apps|Hua et al.|2021|IEEE S&P Workshops|Builds mutual authentication between an app and its model to resist theft and tampering, tested on 43 Google Play apps.|Methods to protect bundled models in your APK.|[Link](https://doi.org/10.1109/SPW53761.2021.00019)|
|77|On-Device AI Models: Advancing Privacy-First Machine Learning for Mobile Applications|Ubale|2025|IJSRCSEIT|Reviews model compression, efficient layers and hardware acceleration for private on-device ML.|Overview of on-device trade-offs.|[Link](https://doi.org/10.32628/cseit2410612397)|
|78|CenterYou++: Lightweight Privacy Enforcement for Android Using Hybrid AI and Cloud Decision Support|Safavi et al.|2025|IEEE ASSIC|Android privacy enforcement that mixes a lightweight on-device agent with cloud policy support.|Contrasts hybrid cloud designs with your fully offline plan.|[Link](https://doi.org/10.1109/assic64892.2025.11158601)|
|79|Visual Privacy Protection in Mobile Image Recognition Using Protective Perturbation|Ye et al.|2022|ACM MMSys|User-side perturbations that protect images sent to a recognition service while keeping accuracy.|Another approach to image privacy on phones, useful as an alternative to redaction.|[Link](https://doi.org/10.1145/3524273.3528189)|
|80|Find and Dig: A Privacy-Preserving Image Processing Mechanism in Deep Neural Networks for Mobile Computation|Huang et al.|2021|IJCNN|Two-tier network with a shallow model on the phone and a deep model in the cloud under differential privacy, cutting memory use by 90%.|Shows efficiency gains and privacy trade-offs for phone-side processing.|[Link](https://doi.org/10.1109/IJCNN52387.2021.9534066)|
|81|A Web-Based Analysis Toolkit for the System Usability Scale|Blattgerste, Pfeiffer|2022|PETRA|Open-source toolkit that computes SUS scores and places them on interpretation scales.|Practical tool for scoring your SUS evaluation.|[Link](https://doi.org/10.1145/3529190.3529216)|
|82|A Conceptual Consent Request Framework for Mobile Devices|Drozd, Kirrane|2023|Information|Consent request designs tested in an app, where functionality-based consent earned the highest SUS score.|Links permission design with SUS-measured usability, relevant to the gallery access prompt.|[Link](https://doi.org/10.3390/info14090515)|
|83|Bring-in Privacy Provisions But Shape-up Attitude Too: Building Privacy Experience in Mobile Platform Ecosystem|Kumar, Venkatachalam|2026|ACM TOCHI|Survey of 160 mobile users showing that skills and attitudes decide whether privacy awareness turns into action.|Supports simple defaults and plain language in your interface.|[Link](https://doi.org/10.1145/3796219)|
|84|Demystifying Android's Scoped Storage Defense|Lee, Chen, Jaeger|2021|IEEE Security and Privacy|Traces Android external storage defenses and assesses scoped storage.|Explains the storage rules your app must work within to read gallery images.|[Link](https://doi.org/10.1109/MSEC.2021.3090564)|
|85|Privacy Concerns and Data Protection Measures in Mobile Application|Gupta|2024|Zenodo|Reviews privacy issues in mobile apps and proposes privacy by design across the development life cycle.|Supports privacy by design in your app architecture.|[Link](https://doi.org/10.5281/zenodo.12787795)|
|86|Mobile-Based Applications: The Legal Challenges on Data Privacy|Qureshi et al.|2023|International Journal of Online Engineering|Reviews legal challenges for mobile apps and points to personal data protection laws.|Context for legal duties of mobile apps that handle personal data.|[Link](https://doi.org/10.3991/ijoe.v19i09.40915)|
|87|A Web-Based Data Privacy Compliance Management System Centered on the Data Privacy Act of 2012 for Business Process Outsourcing Companies †|(Author not recorded)|2022|Zenodo|Builds and tests a compliance system for the Philippine Data Privacy Act with 60 employees and 3 data protection officers.|Philippine-specific source on practical compliance with RA 10173.|[Link](https://doi.org/10.5281/zenodo.7007157)|
|88|On the Applicability of LLMs and SLMs for Privacy-Preserving Named Entity Recognition in Financial Applications|Psarra, Stefanidis|2026|Applied Sciences|Compares many language models for PII detection on the AI4Privacy 43K set, finding small models rival large ones.|Supports small models for PII detection on a device.|[Link](https://doi.org/10.3390/app16073332)|
|89|RedacBench: Can AI Erase Your Secrets?|Jeon et al.|2026|arXiv|Benchmark of 514 texts and 187 policies that scores both security and utility of redaction.|Offers the idea of scoring removal and utility together.|[Link](https://doi.org/10.48550/arxiv.2603.20208)|
|90|Enhancing the De-identification of Personally Identifiable Information in Educational Data|Shen et al.|2025|arXiv|Fine-tuned GPT-4o-mini reached 0.9589 recall on CRAPII and cut cost to about one tenth of Azure AI Language.|Evidence on recall and cost trade-offs when choosing a PII detector.|[Link](https://doi.org/10.48550/arxiv.2501.09765)|

#### Theme 3 gaps

1. Lightweight YOLO papers measure speed on the target task (masks, navigation), not on privacy detection for IDs and cards.
2. Hybrid rule plus model PII detection is mostly tested on text (clinical notes, chats, finance). Few papers apply it to text read from images on a phone.
3. Several papers warn that on-device models can be extracted or bypassed (Guyomard 2025, Hua 2021). The privacy tools in this theme rarely test their own app for that risk.
4. Philippine Data Privacy Act sources focus on organizations and offices. No source covers personal on-device tools that handle ID photos.
5. SUS-based usability results exist for consent screens and other apps, but none measures the effort of reviewing proposed redactions across a whole gallery.

### 17.5 Overall Research Gap

The 90 studies show that detecting sensitive regions, reading text from IDs, masking content and running models on phones each work in isolation. Taken together, they do not show a single offline Android pipeline that (a) targets Philippine IDs and payment cards, (b) is tested under controlled image-quality conditions, (c) lets the user confirm or correct each redaction, and (d) checks the exported image to see whether the chosen fields remain detectable. Your project addresses that combined, narrower problem. This is a provisional gap based on the sources in this matrix and on the RRS testing still to be done. It does not prove that no tool does all four.

**How this project extends the gap:**
- **(b), extended:** it compares layout-anchored redaction with OCR-first redaction under each image condition (Contribution 1).
- **(d), extended:** it is checked through several leak channels, and the same test is applied to existing tools (Contribution 2).
- **(a), extended:** it releases a Philippine benchmark (Contribution 3).
- **(c), changed:** redaction is automatic and correctable rather than user-confirmed, and the vault keeps originals recoverable.

## 18. Additional Sources (not counted toward the 90)

### 18.1 Foundational Works Before 2021 (background only)

|Work|Year|Why keep it|Link|
|---|---|---|---|
|Orekondy, Fritz, Schiele, Connecting Pixels to Privacy and Utility|2018|Early automatic redaction with a privacy and utility trade-off|https://openaccess.thecvf.com/content_cvpr_2018/html/Orekondy_Connecting_Pixels_to_CVPR_2018_paper.html|
|PhotoSafer|2018|Smartphone photo protection|https://arxiv.org/abs/1810.01046|
|VizWiz-Priv|2019|Presence and purpose of private content|https://openaccess.thecvf.com/content_CVPR_2019/html/Gurari_VizWiz-Priv_A_Dataset_for_Recognizing_the_Presence_and_Purpose_of_CVPR_2019_paper.html|
|MIDV-500|2018|Mobile ID video dataset|https://arxiv.org/abs/1807.05786|
|MIDV-2019|2019|Challenging mobile ID OCR|https://arxiv.org/abs/1910.04009|
|Sporici et al., Tesseract preprocessing|2020|Preprocessing for Tesseract|https://doi.org/10.3390/sym12050715|
|ELMAR low-quality OCR|2017|OCR on low-quality images|https://doi.org/10.23919/ELMAR.2017.8124460|
|Hill, Zhou, Saul, Shacham, On the (In)effectiveness of Mosaicing and Blurring as Tools for Document Redaction (PoPETs)|2016|Shows mosaic/blur text redaction is recoverable; basis for the §9.5 mosaic/blur channel|https://petsymposium.org/popets/2016/popets-2016-0047.php|

### 18.2 Other Literature Leads

These are bibliographic leads from earlier notes that are not in the 90-citation matrix. Most need full-text reading and metadata verification before they are cited.

| Title | Year | Possible use | Limitation | Source |
|---|---|---|---|---|
| Story Beyond the Eye: Glyph Positions Break PDF Text Redaction (PoPETs) | 2023 | Methodological model for the tool audit (Contribution 2): 11 PDF redaction tools tested, vendors notified | PDF glyph positions, not photo pixels | https://crysp.petsymposium.org/popets/2023/popets-2023-0069.php |
| Matalov & Arlazarov, ID document template generation from a single image (Computer Optics 49(6)) | 2025 | Prior work for Contribution 1: template matching is precise, data-light, and real-time on mobile | Used for recognition, not redaction | https://computeroptics.ru/KO/Annot/KO49-6/490631.html |
| MIDV2020 "rectified photos" for field localisation and recognition in identity documents | 2021+ | Template-based field localization benchmark | Foreign mock IDs; reading, not redaction | https://www-shadoc.irisa.fr/midv2020-rectified-photos-for-field-localisation-and-recognition-in-identity-documents/ |
| ALIBIS: Assessing and Mitigating the Risk of Sensitive Metadata Leakage in Mobile Image Sharing (Pervasive Mob. Comput.) | 2026 | EXIF metadata leakage on Android; 82.3% of surveyed users don't remove metadata | Does not cover embedded thumbnails or redaction | https://consensus.app/papers/details/f53855b9e4025a85b985db15c60fec6e/ |
| Redacted Text Detection Using Neural Image Segmentation Methods | 2025 | Evaluating redacted regions and remaining visibility | Detecting redaction ≠ proving nothing is recoverable | https://link.springer.com/article/10.1007/s10032-025-00513-1 |
| System and Method for Automated Redaction of PII Using Responsible AI and Computer Vision | 2026 | Redaction workflow design | Metadata unverified | URL not recorded |
| Automatic ID Card Data Detection and Extraction System Based on CNN and OCR | 2024 | Feasibility of detection + OCR | Read the full paper before quoting results | https://jurnal.umb.ac.id/index.php/JSAI/article/view/7269 |
| An ID Badge Information Extractor Based on Object Detection and OCR | 2024 | Localization + structured fields | Badges ≠ gallery IDs | https://ieeexplore.ieee.org/document/10701292/ |
| Recognition of Machine-Readable Zone in Identity Documents: A Review | 2025 | Field localization background | MRZ ≠ all PH ID fields | https://ieeexplore.ieee.org/document/11006992 |
| Hegghammer, OCR with Tesseract, Amazon Textract, and Google Document AI | 2022 | Choosing OCR empirically | Different document types | https://doi.org/10.1007/s42001-021-00149-1 |
| Thammarak et al., Tesseract vs Google Cloud Vision for Thai Vehicle Registration | 2022 | Task-specific OCR benchmarking | Cloud OCR, Thai text | Verify URL |
| Li et al., TrOCR | 2023 | OCR model families | Not shown to be feasible on mobile | https://doi.org/10.1609/aaai.v37i11.26538 |
| Text Detection from Scene Images Using YOLO and Tesseract OCR | 2025 | Staged detection + OCR | Scene text ≠ sensitive fields | https://doi.org/10.1109/ccube66458.2025.11340300 |
| Open-Source OCR Libraries: A Comprehensive Study for Low Resource Language | 2024 | Offline OCR alternatives | Verify metadata | URL not recorded |
| Evaluating OCR Performance on Food Packaging Labels in South Africa | 2025 | OCR depends on image conditions | Methodological only | URL not recorded |
| A Comparative Analysis of OCR Models for Key Information Extraction from Invoices | 2025 | Field-level evaluation | Invoices ≠ IDs | URL not recorded |
| Enhancing Container Number Recognition Through Multi-Model OCR Comparison and Post-Processing | 2026 | Structured-identifier normalization | Verify metadata | URL not recorded |
| A Hybrid Rule-Based NLP and ML Approach for PII Detection in Financial Documents | 2025 | Rules alongside learned models | Text, not images | Verify URL |
| Mask-PII: A Hybrid System for Anonymizing PII in Health Data | 2026 | Field-level masking concepts | Health text | Verify URL |
| pyDeid: Rule-Based Deidentification of Free-Text Medical Records | 2024 | Strengths and limits of deterministic rules | Medical text | Verify URL |
| Intelligent De-identification of Medical Discharge Summaries Using Hybrid NLP | 2025 | Hybrid approaches | Not image redaction | Verify URL |
| Sensitive Information Detection Adopting Named Entity Recognition | 2022 | Semantic field identification after OCR | NER needs OCR boxes to locate pixels | Verify URL |
| Detecting PII Through Natural Language Processing: A Step Forward | 2025 | Field identification context | Text only | Verify URL |
| Privacy-Aware Detection for LLMs Using a Hybrid BiLSTM-HMM Approach | 2025 | Learned PII detection | Not visual | Verify URL |
| A Comparative Study on Textual and Multimodal PII Detection Using LLMs | 2026 | Text vs image-aware methods | LLM ≠ offline phone | Verify URL |
| PIIBench: A Unified Multi-Source Benchmark Corpus for PII Detection | 2026 | Benchmark design | Check for image content | Verify URL |
| Assessing Security and Privacy of Android Official ID Wallet Apps | 2023 | Protecting identity data on Android | Wallet apps, not galleries | Verify URL |

### 18.3 Technical and Legal References

| Source | Use | Link |
|---|---|---|
| Google ML Kit, Text Recognition v2 for Android | OCR integration (choose the bundled model) | https://developers.google.com/ml-kit/vision/text-recognition/v2/android |
| Google ML Kit, Model Installation Paths | Bundled vs unbundled models; supports the offline claim | https://developers.google.com/ml-kit/tips/installation-paths |
| Republic Act No. 10173, Data Privacy Act of 2012 | Data minimization, consent, retention. Confirm interpretation with the adviser. | https://www.privacy.gov.ph/data-privacy-act/ |

## 19. Dataset Leads

These are candidate leads only. Check license, terms, provenance, class definitions, duplicates, annotation quality, and whether training use is permitted. Per §10.1, Roboflow sets are used for detector training only, never for testing.

### 19.1 Philippine ID Datasets

1. Philippine IDs (test-usmly): https://universe.roboflow.com/test-usmly/philippine-ids-kmiv4
2. Philippine IDs (walramworkspace): https://universe.roboflow.com/walramworkspace/philippine-ids-mfzrh
3. Philippine IDs (keeplearning): https://universe.roboflow.com/keeplearning-uthjn/philippine-ids-2loru-qenqb
4. Philippine IDs (yolov8-ctdd4): https://universe.roboflow.com/yolov8-ctdd4/philippine-ids
5. ID name detector: https://universe.roboflow.com/visitor-zwywm/id-name-detector-b2ekw

### 19.2 General Identity-Document Datasets

6. MIDV-500: https://arxiv.org/abs/1807.05786
7. MIDV-2019: https://arxiv.org/abs/1910.04009

### 19.3 Visual Privacy Datasets

8. VizWiz-Priv: https://openaccess.thecvf.com/content_CVPR_2019/html/Gurari_VizWiz-Priv_A_Dataset_for_Recognizing_the_Presence_and_Purpose_of_CVPR_2019_paper.html
9. VPD-100K: https://proceedings.mlr.press/v306/hu26bk.html
10. Orekondy et al. visual privacy/redaction research: https://openaccess.thecvf.com/content_cvpr_2018/html/Orekondy_Connecting_Pixels_to_CVPR_2018_paper.html


## 20. Verification Checklist

### 20.1 Before Building

1. Whether ML Kit's bundled text-recognition library adds the `INTERNET` permission or telemetry through manifest merging. If it does, remove it with `tools:node="remove"` and confirm the app still works in airplane mode.
2. Whether MediaStore sets `DATE_TAKEN` from the kept EXIF `DateTimeOriginal` on the target Android version.
3. The current Ultralytics license terms and LiteRT export support for YOLO11n.
4. The license of each Roboflow PH ID dataset.
5. The Android 14 "selected photos" flow.
6. Calibrate the confidence thresholds, recheck tolerances, and leak rule in a pilot run.
7. Run a full SciSpace/Consensus search on template-based redaction and on audits of mobile or photo redaction tools. The novelty checks behind §5.1 and §5.2 used only a few searches (20 October 2026).
8. Collect current PhilSys (card and ePhilID) and LTO card design variants for the templates, using only publicly published specimen images.

### 20.2 Before Manuscript Submission

1. Open each paper and confirm authors, venue, year, DOI, and URL. Some author lists are shortened to the first author. Dagger entries and §18.2 leads need full reading.
2. Distinguish peer-reviewed studies from preprints, app listings, repositories, and developer claims.
3. Do not compare metrics across papers or systems unless datasets, labels, splits, and metrics match.
4. Record access dates and versions for apps and repositories.
5. Treat the GalleryKeeper and PrivyMark observations as preliminary until they are repeated with controlled mock images (§16.2).
6. Do not claim redaction itself is novel. The defensible contribution is the combination in §5, measured on the defined PH document set.
7. Confirm the RA 10173 interpretation and the SDG mapping with the adviser.
8. Write the final RRL chapter as a thematic narrative. §17 is a source register, not a substitute for that chapter.
