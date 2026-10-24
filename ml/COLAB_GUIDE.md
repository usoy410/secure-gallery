# Train the detector on Google Colab (issue #9)

This guide is for the person who runs the full detector training. You need a Google account and about 15 minutes of your own time. Colab then works for 1.5 to 2.5 hours on its own.

You do not need any dataset. The notebook generates every training image itself, and all of them are fictional. Never add a real ID or card photo (CLAUDE.md, hard rules).

## What you get

A file called `detector_model.zip`. Send it back, or attach it to issue #9. It holds:

- `detector.tflite`: the new model for the app (INT8, about 3 MB).
- `detector.json`: its metadata, which the app reads.
- `best.pt`: the PyTorch weights, for the model card and later fine-tuning.
- `eval_device_photos.json`, `eval_benchmark_test.json`, `results.csv`, `args.yaml`, `run_info.json`: the numbers for `ml/MODEL_CARD.md`.

## Steps

### 1. Open the notebook

1. Go to <https://colab.research.google.com> and sign in.
2. Choose **File > Open notebook > GitHub**.
3. Type `usoy410/secure-gallery`. If the repo is private, tick **Include private repos** and allow access.
4. Pick `ml/colab_train.ipynb` on the `master` branch.

### 2. Turn on the GPU

1. Choose **Runtime > Change runtime type**.
2. Pick **T4 GPU** and click **Save**.

### 3. Add a GitHub token (only if the repo is private)

The notebook downloads the repo inside Colab, so a private repo needs a token.

1. On GitHub, open **Settings > Developer settings > Personal access tokens > Fine-grained tokens > Generate new token**.
2. Under **Repository access**, choose **Only select repositories** and pick `secure-gallery`. Under **Permissions**, set **Contents** to **Read-only**. Set it to expire in 7 days.
3. Copy the token.
4. In Colab, click the **key icon** on the left (Secrets), click **Add new secret**, name it `GITHUB_TOKEN`, paste the token, and switch on **Notebook access**.

If the repo is public, skip this step.

### 4. Run it

1. Choose **Runtime > Run all**.
2. If Colab asks whether the notebook may read `GITHUB_TOKEN`, click **Grant access**.
3. Keep the browser tab open. Colab can stop a session if the tab closes or stays idle for a long time.

Rough timing on a T4:

| Step | Time |
|---|---|
| Install packages | about 5 minutes |
| Generate about 6,000 training cards and the benchmark | 20 to 30 minutes |
| Train 100 epochs | 1 to 2 hours |
| Check corners and export | about 5 minutes |

### 5. Read the result

Near the end, the notebook prints one of two lines:

- **`CORNER CHECK PASSED`**: every corner of the 20 device photos lands within 3% of the card width. That is what the emulator leak test needs (PR #64).
- **`CORNER CHECK FAILED`**: the model is still too far off on at least one photo. Send the zip anyway. Its numbers show how far off it is and what to change next.

Just above that, a line like this shows the corner error:

```
Photos: 20 (0 missed) | corner error median 1.20% | p95 2.40% | max 2.80% of card width
```

The interim model in the app today scores median 4.6%, p95 8.3% and max 8.6%.

### 6. Send the zip back

The last cell downloads `detector_model.zip` to your computer. Send it in the chat, or attach it to issue #9. Then delete the `GITHUB_TOKEN` secret in Colab and the token on GitHub.

## If something goes wrong

| What you see | What to do |
|---|---|
| `No GPU` | Runtime > Change runtime type > T4 GPU, then Run all again. |
| `Cannot connect to GPU backend` | Colab's free GPUs are busy. Try again later, or use a Colab Pro session. |
| Clone fails with `Authentication failed` | The token is missing, expired or lacks access to the repo. Redo step 3. |
| The session disconnects during training | Run all again. Training starts over. |
| Training is slow (over 3 minutes per epoch) | Check that the runtime says T4 GPU and not CPU. |
| Any other red error | Copy the last 30 lines of the error and send them. |

## After you send the zip

The rest is done in the repo. `ml/MODEL_UPDATE_GUIDE.md` has every step, so anyone on the team can do it:

1. Commit `detector.tflite` (Git LFS) and `detector.json` to `android/app/src/main/assets/`.
2. Fill in `ml/MODEL_CARD.md` with the numbers from the zip.
3. Regenerate the Android test fixtures (`python -m ml.android_fixtures`).
4. Re-run PR #64's emulator leak test on the new model.
