# Issue #9 detector candidate evidence

The app asset in this review commit uses **W8A16 (INT8 weights, INT16 activations)**,
not full static INT8. See the [model card](../../../ml/MODEL_CARD.md) for its status,
training provenance, results and remaining checks. The checkpoint was not retrained.

| Evidence | Meaning |
|---|---|
| `w8a16-evaluation.json` | Selected candidate, same 20 fixtures: median 1.58%, p95 2.45%, max 2.79%, zero misses |
| `w8a16-verification.json` | Asset SHA256, size and actual tensor types |
| `int8-evaluation.json` | Full static INT8 comparison: max 6.11%, not selected |
| `checkpoint-evaluation.json` | Source PyTorch checkpoint: max 3.05% |
| `uploaded-float32-evaluation.json` | Uploaded mislabeled FLOAT32 model: max 3.05% |
| `benchmark-pytorch.json` | Supplied PyTorch benchmark results; not W8A16 benchmark results |
| `calibration-provenance.json` | Separate synthetic calibration set; seeds 600000–600299 |
| `export-versions.json` | Versions used for local exports and comparisons |
| `training-args.yaml`, `training-results.csv` | Unmodified training records from the supplied ZIP |

Evaluation uses the 20 synthetic redaction fixtures at commit
`3eb72eb0b1bd81aea77cce8f986bfc8142b4637f`, confidence 0.25, NMS IoU 0.7,
640 px square letterbox (`rect=False`), EXIF-upright images and the most confident
detection per image. These fixtures are not added to this branch here; use the
referenced dependency branch/commit when reproducing the geometry check.

Source `best.pt` SHA256:
`2f6856f0dbcfcb38fa59d0be09e99688cd8c71b7fa93214f258399655d69b4bb`.
Keep that checkpoint and the supplied ZIP in external artifact storage, not Git.
The upload lacked `run_info.json`, so its training commit and completion time are unknown.

The W8A16 model contains 97 INT8 tensors and 476 INT16 tensors. Its graph input and
output are FLOAT32, with shapes `[1, 3, 640, 640]` and `[1, 15, 8400]`. Do not infer
weight precision from graph I/O alone or from a filename.

The five synthetic Android fixture photos and recorded detector tensors are regenerated
from the current generator and this candidate. Kotlin post-processing matches the
Ultralytics reference under the existing tolerances.

**Pending:** Android device inference/delegate support and performance, the dependent
end-to-end redaction/leak test, and W8A16 evaluation on the full held-out benchmark.
A geometry pass does not establish leak-test success. No thresholds were relaxed.
This commit is for review and does not close issue #9.
