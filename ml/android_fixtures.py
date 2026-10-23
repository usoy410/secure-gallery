"""Test fixtures for the Android detector (#16), built from the bundled model.

Writes two things, both synthetic only:
- `android/app/src/androidTest/assets/fixtures/`: five generated card photos (one per template
  family, seeds outside the training set) and `fixtures.json` with each one's true class, for
  the instrumented test on the emulator.
- `android/app/src/test/resources/detector/`: for each photo, the model's raw output tensor
  (only anchors with a class score of at least 0.01; the rest are zero) and Ultralytics' own
  predictions on the same photo, so the Kotlin post-processing can be checked against the
  reference implementation without running the model on the JVM.

Usage: python -m ml.android_fixtures --model android/app/src/main/assets/detector.tflite
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from ml.generate import generate_single_sample
from templates.validate import load_json

REPO_ROOT = Path(__file__).resolve().parents[1]
ANDROID = REPO_ROOT / "android" / "app" / "src"
FIXTURES = [
    ("philsys_card", "philsys-card", 90001),
    ("philsys_ephilid", "philsys-ephilid", 90002),
    ("lto_current", "lto-dl-current", 90003),
    ("lto_previous", "lto-dl-previous", 90004),
    ("payment_card", "payment-card-front", 90005),
]
FIXTURE_LONG_SIDE = 800  # small files; the app decodes to 640 anyway
SPARSE_MIN_SCORE = 0.01


def make_photos(out_dir: Path) -> list[dict]:
    out_dir.mkdir(parents=True, exist_ok=True)
    expected = []
    for name, template_id, seed in FIXTURES:
        template = load_json(REPO_ROOT / "templates" / f"{template_id}.json")
        img, label = generate_single_sample(template, seed=seed, composite=True)
        img = img.convert("RGB")
        scale = FIXTURE_LONG_SIDE / max(img.size)
        img = img.resize((round(img.width * scale), round(img.height * scale)))
        path = out_dir / f"{name}.jpg"
        img.save(path, "JPEG", quality=85)
        expected.append({"file": path.name, "document_class": label["document_class"]})
    (out_dir / "fixtures.json").write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8")
    return expected


def record_tensors(
    model_path: Path, photos_dir: Path, out_dir: Path, conf: float, iou: float
) -> None:
    import cv2
    from ai_edge_litert.interpreter import Interpreter
    from ultralytics import YOLO
    from ultralytics.data.augment import LetterBox

    out_dir.mkdir(parents=True, exist_ok=True)
    interpreter = Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    outp = interpreter.get_output_details()[0]
    size = int(inp["shape"][-1])
    reference = YOLO(str(model_path), task="pose")
    # Class names come from the metadata the app reads, not from the .tflite file.
    meta = json.loads((model_path.parent / "detector.json").read_text(encoding="utf-8"))
    class_names = meta["class_names"]

    for name, _, _ in FIXTURES:
        bgr = cv2.imread(str(photos_dir / f"{name}.jpg"))
        boxed = LetterBox(new_shape=(size, size), auto=False)(image=bgr)
        x = boxed[..., ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0
        interpreter.set_tensor(inp["index"], np.ascontiguousarray(x))
        interpreter.invoke()
        raw = interpreter.get_tensor(outp["index"])[0]  # [channels, anchors]
        n_classes = raw.shape[0] - 4 - 8
        keep = np.where(raw[4 : 4 + n_classes].max(axis=0) >= SPARSE_MIN_SCORE)[0]

        result = reference.predict(bgr, imgsz=size, conf=conf, iou=iou, verbose=False)[0]
        expected = [
            {
                "document_class": class_names[int(c)],
                "confidence": round(float(s), 4),
                "box": [round(float(v), 1) for v in b],
                "corners": [[round(float(px), 1), round(float(py), 1)] for px, py in k],
            }
            for c, s, b, k in zip(
                result.boxes.cls,
                result.boxes.conf,
                result.boxes.xyxy,
                result.keypoints.xy,
                strict=True,
            )
        ]
        record = {
            "image": {"width": bgr.shape[1], "height": bgr.shape[0]},
            "input_size": size,
            "channels": int(raw.shape[0]),
            "anchors": int(raw.shape[1]),
            "sparse": [[int(a)] + [round(float(v), 5) for v in raw[:, a]] for a in keep],
            "reference": expected,
        }
        (out_dir / f"{name}.json").write_text(json.dumps(record) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    default_model = ANDROID / "main" / "assets" / "detector.tflite"
    parser.add_argument("--model", type=Path, default=default_model)
    parser.add_argument("--conf", type=float, default=0.35, help="as in assets/detection.json")
    parser.add_argument("--iou", type=float, default=0.5, help="as in assets/detection.json")
    args = parser.parse_args()
    photos = ANDROID / "androidTest" / "assets" / "fixtures"
    make_photos(photos)
    recorded = ANDROID / "test" / "resources" / "detector"
    record_tensors(args.model, photos, recorded, args.conf, args.iou)
    print(f"Wrote fixtures to {photos} and recorded tensors next to the unit tests")


if __name__ == "__main__":
    main()
