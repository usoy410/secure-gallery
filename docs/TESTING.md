# Testing

This guide covers how to run each test suite, what each group of tests proves, and where to find the evidence for the paper.

All test data is synthetic. Never add a real ID or card image to the repo, not even to reproduce a bug (CLAUDE.md, hard rules).

## Run the Python tests

You need Python 3.11, Tesseract and two system libraries for the OCR judge. The CI workflow (`.github/workflows/python.yml`) installs the same packages.

```bash
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng fonts-dejavu-core fonts-liberation \
  libgl1 libglib2.0-0
pip install -e ".[dev,leaktest]"

ruff check .   # lint, as CI runs it
pytest         # every Python test
```

On the first run, PaddleOCR (the leak-test judge) downloads its models from Hugging Face into `~/.paddlex/official_models`. After that, everything runs offline. The full suite takes about 2 minutes on a laptop CPU.

To run one group, give its folder, for example `pytest validation/leaktest`. Set `CI=1` to get the same behaviour as CI. One test is skipped unless you install the `train` extra (`ultralytics` and `torch`).

## What each test group proves

| Folder | Issue | Spec | What the tests prove |
|---|---|---|---|
| `tests/` | #1 | §8.8 | The test runner works (a smoke test). |
| `templates/tests/` | #3 | §8.1, §11 | Every template matches `templates/schema.json`. Each variant covers the fields §8.1 says to redact. Sensitive and visible fields do not overlap. PhilSys has a QR code field. |
| `ml/tests/test_generate.py` | #5, #45 | §10.1 | The generator renders every template with fictional values. Card numbers come from published test ranges and pass Luhn. Every card has a SPECIMEN watermark. The same seed gives byte-identical files. OCR reads at least 95% of 50 clean fields exactly. The payment card back stays readable. |
| `ml/tests/test_conditions.py` | #6 | §9.1, §10.1 | Eight image conditions, each with 3 severities. Field polygons follow rotation, perspective and crop (checked by mask IoU). Layout jitter stays within its limits. |
| `ml/tests/test_negatives.py`, `test_failed_redactions.py`, `test_splits.py`, `test_dataset_cli.py` | #7 | §9.2, §10.1 | Negatives have no fields. Each failed-redaction type (complete, partial, misplaced, ineffective) covers what it claims. All variants of one identity land in the same split. One command builds the dataset and its manifest. |
| `ml/tests/test_converter.py`, `test_train.py`, `test_eval.py`, `test_metrics.py`, `test_export.py` | #8 | §8.6, §8.7 | Labels convert to YOLO pose format. Training, corner-error evaluation and LiteRT export run end to end (with the model mocked). Metrics match hand-computed values. |
| `validation/localize/tests/` | #10 | §5.1, §8.2 | layout_only covers every sensitive field on a rotated card. ocr_first reads a card with no template. hybrid is the union of both. A missing corner is inferred. The whole document is blacked out only when nothing else works. Redactions are solid fills saved as JPEG q95. No OCR text is written to disk. |
| `validation/leaktest/tests/test_leaktest.py` (`--fail-on-leak`) | #18 | §9.5 | `has_leak` follows the RQ4 rule: a text field leaks when its text comes back, a picture field when it is not fully covered, a file when it keeps a thumbnail or identifying metadata. Opacity alone on a text field (the strict reading) and a mosaic risk flag do not fail the CLI. |
| `validation/localize/tests/test_rule_parity.py` | #17 | §8.2 | The field rules find the same boxes in the shared `rule_cases.json` as the app's Kotlin port. Runs without Tesseract. |
| `validation/leaktest/tests/test_leaktest.py` | #11 | §9.2, §9.5 | Character error rate and the 4-digit rule work. A solid fill passes the opacity check even after JPEG noise. Half covers and see-through fills fail. Known failed redactions are caught. The CLI writes per-field results and a summary. |
| `validation/leaktest/tests/test_channels.py` | #12 | §9.5 | Each recovery channel (clipped characters at a box edge, a see-through fill, mosaic or blur) flags its positive fixture and passes its negative one. Mosaic is a risk flag, not a leak. |
| `validation/leaktest/tests/test_filemeta.py` | #13 | §9.5 | An unredacted EXIF thumbnail leaks and a redacted one does not. Metadata reports tag names, never values. The card-number check counts Luhn candidates correctly. |
| `validation/report/tests/` | #14 | §6 (RQ4), §9.1 | Wilson intervals match published values. Over-redaction counts only non-sensitive card area. The leak rule treats text and pictures as agreed. The OCR pad is tuned on val and the report scores the test split only. The example gallery stays under its size limit. |

## Run the Android tests

You need JDK 21 and the Android SDK with API 36.

```bash
cd android
./gradlew testDebugUnitTest -Proborazzi.test.record=true   # JVM, Robolectric and screenshot tests
./gradlew lint
./gradlew connectedDebugAndroidTest                         # needs a device or emulator
```

| Test | Issue | What the test proves |
|---|---|---|
| `AppContainerTest` | #2 | The manual DI container (`AppContainer`) starts with an app context. |
| `ui/InboxScreenshotTest` | #2 | Roborazzi records the empty review inbox in light and dark themes. CI uploads the screenshots as the `roborazzi-screenshots` artifact. |
| `PlaceholderInstrumentedTest` | #2 | The emulator job runs and sees the right package name. |
| `data/ItemStateTest` | #15 | Item states move only along §8.4: for example a restored image is never flagged again, and a vaulted original never goes back to public. |
| `data/ItemSchemaTest` | #15 | The Room `items` table has exactly the §8.4 columns and nothing that could hold OCR text. The box column accepts only known field types and numbers. |
| `scan/ImageQueryTest` | #15 | The real MediaStore query, run against a fake MediaStore table, keeps finished still images from other apps. It drops GIFs, the app's own redacted copies (by owner or by the `.sg-redacted.` name marker) and files still being written. |
| `scan/GalleryScannerTest` | #15 | The scan indexes every image, hashes them one at a time, skips unchanged images on the next scan, re-indexes changed ones unless they are already flagged, and marks a file deleted mid-scan as failed. |
| `scan/PhotoAccessTest` | #15 | Each Android version asks for the right photo permission, and Android 14 "selected photos" counts as partial access. |
| `ui/ScanScreenshotTest` | #15 | Roborazzi records the scan screen: no access, scanning (light and dark), done, and done with selected photos only. |
| `vault/VaultCryptoTest` | #20 | AES-GCM wrapping round-trips. A wrong key, changed associated data or a flipped byte is rejected (GCM tag), and a vault file renamed to another item does not decrypt. |
| `vault/VaultTest` | #20 | Setup gives a 24-character recovery code once. PIN and recovery-code unlock work, a wrong PIN is refused and then slowed down (also across restarts), and a new PIN can be set after recovery. A copied vault cannot be opened by PIN without this phone's Keystore key. Write-and-verify deletes a corrupted file. The key file holds no PIN, code or data key. |
| `vault/RecoveryAndLimiterTest` | #20 | Recovery codes use an unambiguous alphabet and typed codes are normalized. The wrong-PIN delay doubles from 30 s to a 1-hour cap. |
| `vault/NoPlaintextOnDiskTest` | #20 | After an original is stored and read back, no file in the app's data, files or cache folders contains its bytes. A failed check marks the item `FAILED` and leaves the original untouched. |
| `vault/KeystoreVaultTest` (instrumented) | #20 | On the emulator, the real Keystore HMAC key is stable, and a full setup, store, read and recovery round trip works. |
| `vault/VaultBiometricTest` | #21 | A biometric-unlocked cipher unwraps the same data key. When enrollment changes, biometric unlock turns itself off, the PIN still works, and the user can re-enroll. A wrong cipher is rejected. |
| `vault/VaultSessionTest` | #21 | The unlocked session locks after 2 idle minutes, activity restarts the timer, locking is immediate on demand (as on background), and every lock wipes the key bytes. |
| `ui/SecureWindowTest` | #21 | `FLAG_SECURE` is set while a secure screen is shown and cleared when it leaves. |
| `ui/VaultScreenshotTest` | #21 | Roborazzi records the vault: locked (light and dark), PIN entry, wrong PIN, too many attempts, fingerprint changed, unlocked, and not set up. |
| `vault/VaultArchiveTest` | #26 | Export, wipe, then import on a "new phone" restores byte-identical originals under the new PIN; the recovery code still works and the old PIN does not. The export holds no plaintext and no PIN wrap. A wrong code, a damaged original, a missing item, a non-zip file, an entry name that tries to leave the vault folder, or a non-empty vault are all rejected with nothing changed. |
| `vault/VaultTransferViewModelTest` | #26 | The uninstall warning shows on unlock until "Do not show this again" is ticked. Export and import work from the vault screen; a wrong code keeps the dialog open; a successful import locks the session. |
| `ui/VaultScreenshotTest` (export and import) | #26 | Roborazzi records the unlocked vault with Export and Import, the result messages, the first-use warning dialog, and the import dialog with a wrong code. |
| `setup/SetupFlowTest` | #28 | Setup runs in order and skips the biometric step on phones without one. An interrupted setup resumes after the last saved step, goes back to the PIN if no vault exists, and skips ahead if the vault exists but the step was not saved. |
| `setup/SetupViewModelTest` | #28 | PINs must have 6 digits and match. The vault is created only after the user confirms the recovery code, with the exact code shown, and setup leaves an unlocked session. Going back discards the code. Setup interrupted at the code resumes at the PIN with no vault. Finishing marks setup done. |
| `ui/SetupScreenshotTest` | #28 | Roborazzi records every setup screen in light and dark: welcome, photo access, PIN (and mismatch), recovery code, biometric opt-in, and the cloud-backup and uninstall notice. |
| `detect/YoloPosePostProcessorTest` | #16 | The raw detector output becomes detections: best class above the threshold, class-agnostic NMS, normalized or pixel coordinates mapped back through the letterbox to the original photo, corners ordered top-left clockwise, and a cap on detections. |
| `detect/RecordedTensorTest` | #16 | On tensors recorded from the bundled model for five synthetic photos, the Kotlin post-processing gives the same class, confidence (within 0.002), box and corners (within 2.5 px) as Ultralytics. Regenerate with `python -m ml.android_fixtures`. |
| `detect/PreprocessTest`, `detect/DetectorConfigTest` | #16 | Letterboxing with grey bars from an already downsampled decode; thresholds and model shape come from `detector.json` and `detection.json`. |
| `data/MigrationTest` | #16 | A version 1 database (built from the committed schema) upgrades to version 2 and keeps its items. |
| `scan/GalleryScannerTest` (detection) | #16 | The scan decodes at detection size only, keeps the most confident detection's class, confidence and corners, and marks images with none `NOT_DETECTED`. |
| `detect/DetectorInstrumentedTest` (instrumented) | #16 | On the emulator, the bundled model loads from the app's assets and finds the right class on at least 4 of 5 synthetic photos it never saw in training. |
| `localize/FieldRulesTest` | #17 | The app's field rules give the same boxes as the Python harness on every case in `validation/localize/tests/rule_cases.json` (row merging, reading order, next-line addresses, excluded words, Luhn). OCR words and lines never print their text. |
| `localize/GeometryTest` | #17 | A missing corner is inferred as in the harness. The `Matrix.setPolyToPoly` homography maps the template's unit square onto a perspective card exactly. The bundled templates cover every class, and picture fields get the 3% margin. |
| `localize/HybridLocalizerTest` | #17 | OCR reads the rectified card (1400 px wide). Every template field is covered, and an OCR box that reaches past its template region is added, never cut. Fully read at confidence 0.6 or more gives `REDACTED`, anything less `NEEDS_CHECK`. No corners runs the OCR-first rules only; no corners and no field gives one whole-document box. An unknown variant covers every variant. A dark card gets an inverted pass. Boxes come back in original-image pixels. |
| `localize/NoPlaintextTest` | #17 | After a full scan with localization, the stored item, the database files on disk and the log hold none of the text OCR read. |
| `scan/GalleryScannerTest` (localization) | #17 | Detected items are decoded at OCR size only (long side 2048) and get their boxes and level; the OCR bitmap is recycled. A failed localization keeps the item for the next scan. |
| `localize/LocalizerInstrumentedTest` (instrumented) | #17 | On the emulator, the bundled detector and bundled ML Kit model find fields on at least 4 of the 5 synthetic photos, with every box inside the photo. It logs counts and timings, never text. |
| `data/MigrationTest` (version 2) | #18 | A version 2 database upgrades to version 3 and keeps its items, with no redacted copy linked yet. |
| `redact/RedactedJpegTest` | #18 | Every pixel inside a tilted box is solid fill after JPEG q95 (the §8.5 99.5% rule) and nothing outside changes. The copy's EXIF keeps only the capture date and its offset, with orientation 1: no GPS, make, model, software, maker note or thumbnail. A malformed date is never written. |
| `redact/RedactorTest` | #18 | The copy is saved and linked to the item, the original's bytes are unchanged, and the full-size bitmap is recycled. A failing save is retried, then the item becomes `FAILED`; a deleted original becomes `FAILED` at once. |
| `redact/RedactionInstrumentedTest` (instrumented) | #18 | On the emulator's real MediaStore, 20 synthetic photos with GPS, device tags and an unredacted thumbnail (every fourth stored rotated) go through detection, localization and redaction. Each gets a copy in its original's album, with the `.sg-redacted.` marker, the same `DATE_TAKEN` (§20.1 item 2), only the capture date in EXIF and upright pixels. Each original's hash is unchanged. The copies and boxes are exported for the leak-test job. |
| CI job "Leak Test on Device Redactions" | #18 | `python -m validation.leaktest --fail-on-leak` judges the emulator's 20 copies against their labels by the RQ4 rule: no text field's text may come back and every face, QR code and signature must be fully covered (D11), and no file may keep a thumbnail or identifying metadata (D13). Fixtures come from `python -m ml.android_redaction_fixtures`. |
| `scan/ImageQueryTest` (no selection) | #65 | The MediaStore query sends no selection, so MediaProvider cannot drop rows for the app. GIFs, the app's own files, redacted copies and pending files are filtered in Kotlin, and a null column keeps the photo. |
| `AppContainerTest` (fallback) | #65 | When the detector or the localizer throws while being built, both become null and the scan still indexes and hashes photos. |
| `ui/ScanScreenshotTest` (failed, found and checked) | #65 | A failed scan shows "Scan failed" and "Try again", never "Scan complete". The done screen shows photos found and checked separately. |

CI also checks the merged manifest and fails if `android.permission.INTERNET` appears (CLAUDE.md, fully offline). The emulator job runs nightly, and on pull requests that touch `vault/`, `scan/`, MediaStore code or `androidTest`.

## Reports and visual evidence

Some results are too slow to compute in unit tests, so they live in `reports/` and come from one command each. Charts and example images are committed so you can review them without running anything.

| Report | Command | Run time | Contents |
|---|---|---|---|
| `reports/rq4/` | `python -m validation.report rq4` | About 1 hour on CPU | Leak rate per strategy, condition and severity, with 95% intervals. Also 8 charts, a README for each column, and `examples/`. |
| `reports/rq4/examples/` | `python -m validation.report rq4-examples` | Seconds, no OCR | 11 synthetic test images. Each shows the original next to the copy each strategy saved. Red outlines mark text the leak test recovered. Orange outlines mark pictures that are not fully covered. |

`rq4-examples` reads the files an earlier `rq4` run left in `data/benchmark/rq4_work`. Run `rq4` first on a fresh clone.

## Adding a test

- Put the test next to the code it covers, in that package's `tests/` folder.
- Start the file with a docstring that names the issue and lists the acceptance criteria the test checks.
- Build fixtures from the generator (`ml.generate`) or draw them in the test. Never load a real photo.
- Never assert on, print or save OCR text from a card outside the test's temporary folder (CLAUDE.md, hard rules).
- Add a row to the table above when you add a new test group.
