# Put a newly trained detector into the app (issue #9)

Follow this guide after `ml/colab_train.ipynb` (see `ml/COLAB_GUIDE.md`) gives you `detector_model.zip`. Anyone on the team can do it, with or without an AI agent. It takes about an hour, most of it waiting for CI.

## What you need

- The zip from Colab.
- A computer with `git`, `git-lfs` and Python 3.11. Android Studio is optional: CI builds and tests the app.
- Push access to `usoy410/secure-gallery`.

## 1. Check the result before anything else

Unzip `detector_model.zip` and open `eval_device_photos.json`. Look at `max_pct` (the worst corner of the 20 emulator photos, in percent of the card width) and `missed` (photos where nothing was found).

| Result | What it means | What to do |
|---|---|---|
| `max_pct` at or under 3.0 and `missed` 0 | Passes the geometry gate; device redaction/leak checks are still required. | Go on to step 2. |
| `max_pct` over 3.0, but well under the interim model's 8.6 | Better, not enough yet. | Step 2 still improves the app. PR #64's leak test will stay red. See "If the corner check failed" below. |
| `max_pct` about 8 or more, or `missed` above 0 | The run did not help. | Do not ship it. See "If the corner check failed". |

Also note `p95_pct` and `median_pct`, and in `eval_benchmark_test.json` the `map50` value. They go in the model card.

## 2. Set up the repo

```bash
git lfs install
git clone https://github.com/usoy410/secure-gallery.git   # skip if you already have it
cd secure-gallery
git checkout master && git pull
git checkout -b model-v1
pip install -e ".[dev,train]"
```

## 3. Copy the model in

```bash
cp ~/Downloads/detector_model/detector.tflite android/app/src/main/assets/detector.tflite
cp ~/Downloads/detector_model/detector.json   android/app/src/main/assets/detector.json
```

Do not commit `best.pt`. It is ignored by git on purpose. Keep it in Google Drive with the rest of the zip.

Check that the app will read the metadata. It must show the three classes in this order and normalized coordinates:

```bash
python -c "import json; m = json.load(open('android/app/src/main/assets/detector.json')); print(m['class_names'], m['output_coordinates'], m['input_size'], m['quantization'])"
# expected: ['philsys', 'lto', 'payment_card'] normalized [640, 640] int8 (or verified w8a16)
```

The class order, coordinates and input shape must match the app (`detect/DetectorConfig.kt`).
The issue #9 candidate uses `w8a16`: INT8 weights with INT16 activations and FLOAT32
graph input/output. Its metadata must say `w8a16`, not `int8` or `fp16`. Verify actual
tensor types as well as the label. Android runtime/delegate support and device performance
remain required checks before accepting this variant.

## 4. Check the exported model on the device photos

The notebook may have checked the full-precision weights. The phone runs the exported file (INT8 or W8A16), so check that file too:

```bash
git fetch origin ccr-8bbefdfe-vrdbj7
git checkout origin/ccr-8bbefdfe-vrdbj7 -- android/app/src/androidTest/assets/redaction   # only while PR #64 is open
python -m ml.eval --model android/app/src/main/assets/detector.tflite \
  --photos android/app/src/androidTest/assets/redaction --max-corner-pct 3
git restore --staged --worktree android/app/src/androidTest/assets/redaction   # only if you fetched them above
```

It should print `Corner check passed`. If it fails while step 1 passed, INT8 lost precision: note both numbers in the model card and see "If the corner check failed".

## 5. Regenerate the detector test fixtures

The unit test `RecordedTensorTest` compares the app's post-processing with Ultralytics on recorded model outputs. A new model needs new recordings:

```bash
python -m ml.android_fixtures
```

This rewrites `android/app/src/test/resources/detector/*.json` (and the five photos in `androidTest/assets/fixtures/`, which come out the same).

## 6. Fill in the model card

Open `ml/MODEL_CARD.md` and replace every `TODO` with the numbers from the zip: `run_info.json` (commit, GPU, epochs, finish time), `results.csv` (last row), `eval_benchmark_test.json` and `eval_device_photos.json`. Leave the thresholds in `android/app/src/main/assets/detection.json` alone: they are tuned on val later (M3).

## 7. Run the fast checks

```bash
ruff check .
pytest ml/tests
cd android && ./gradlew testDebugUnitTest && cd ..   # optional if you have the Android SDK; CI runs it
```

## 8. Commit and open a pull request

```bash
git add android/app/src/main/assets/detector.tflite android/app/src/main/assets/detector.json \
        android/app/src/test/resources/detector ml/MODEL_CARD.md
git lfs ls-files            # must list android/app/src/main/assets/detector.tflite
git commit -m "feat(ml): full detector model (#9)"
git push -u origin model-v1
```

Open a pull request into `master` with `Closes #9` in the description. Paste the corner numbers from step 1 and step 4.

The emulator job only starts on its own for some folders. Start it by hand so the new model runs on the emulator: on GitHub, open **Actions > Android CI > Run workflow**, pick the `model-v1` branch and run it. Merge when "Build, Unit Tests & Lint" and "Connected Emulator Tests (API 34)" are green.

## 9. Finish PR #64 (auto-redaction, issue #18)

PR #64 waits for this model. After step 8 is merged:

```bash
git checkout ccr-8bbefdfe-vrdbj7
git pull
git merge origin/master        # resolve conflicts if any, keeping both sides' changes
git push
```

CI then runs the emulator test and "Leak Test on Device Redactions" on the new model.

- **Green:** merge PR #64. It closes #18.
- **Still red:** download the `device-leak-results` artifact from the run and open `leaks_wide.csv`. Rows with `leak` under `opacity` for `face_photo` or `signature` mean corners are still off on those photos. Text leaks (`visible_pixels`, `partial_chars`) or `metadata` leaks would be a new problem: open an issue with the rows.

## If the corner check failed

Try these in order. Each one is a change to the notebook's settings cell or to `configs/detector_train.yaml`, then another Colab run.

1. **Train longer:** set `EPOCHS = 200`. Look at `results.csv`: if the pose loss was still dropping in the last epochs, more epochs help.
2. **More data:** raise `count_per_variant` from 40 to 80 in `configs/detector_train.yaml`. Generation and training both take about twice as long.
3. **Higher input size:** corners are more precise at 800 px than at 640 px, but the app's detector would need `input_size` 800 and becomes slower. Ask the team first: it changes §8.7 performance numbers.

Do not raise the 3% picture margin or loosen the leak test to get green. Both are research decisions (see the comments on PR #64).

## Using an AI agent for these steps

If you have a coding agent (Claude Code or similar), give it this file and the zip, and ask it to "follow ml/MODEL_UPDATE_GUIDE.md with this detector_model.zip and stop at any step that fails". Every step above can be checked from its output, so you can review what it did.
