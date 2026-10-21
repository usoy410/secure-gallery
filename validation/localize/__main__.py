"""CLI: python -m validation.localize --strategy {ocr_first,layout_only,hybrid} <dataset>

For every labeled image under <dataset> it writes, under <out>/<strategy>/:
  <name>.jpg         the redacted image (solid fill, JPEG q95)
  <name>.boxes.json  the redaction boxes (field type, source, polygon); no OCR text
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from PIL import Image

from validation.localize.core import (
    STRATEGIES,
    load_rules,
    load_templates,
    localize,
    redact,
    save_jpeg,
)


def _labels(dataset: Path) -> list[Path]:
    return sorted(
        p for p in dataset.rglob("*.json") if not p.name.endswith(".boxes.json")
    )


def _image_for(label_path: Path, label: dict[str, Any]) -> Path:
    name = label.get("image_path") or label_path.with_suffix(".png").name
    return label_path.parent / name


def _model_corners(model_dir: Path, rel: Path) -> dict[str, Any] | None:
    """D8 detector output for one image: {"variant": ..., "card_corners": [...]}."""
    path = model_dir / rel.with_suffix(".json")
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def run(
    dataset: Path,
    strategy: str,
    out: Path,
    corners: str = "gt",
    model_dir: Path | None = None,
) -> list[Path]:
    """Localize and redact every labeled image. Returns the written box files."""
    templates = load_templates()
    rules, excluded = load_rules()
    written = []
    for label_path in _labels(dataset):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        if "card_corners" not in label:
            continue  # not an image label (e.g. a manifest)
        image_path = _image_for(label_path, label)
        if not image_path.exists():
            raise FileNotFoundError(f"Label {label_path} points to missing image {image_path}")
        rel = label_path.relative_to(dataset).with_suffix("")

        if corners == "model":
            detected = _model_corners(model_dir, rel) if model_dir else None
            is_document = detected is not None
            variant = detected.get("variant") if detected else None
            raw_corners = detected.get("card_corners") if detected else None
        else:
            is_document = not label.get("is_negative", False)
            variant = label.get("variant")
            raw_corners = label.get("card_corners")

        with Image.open(image_path) as img:
            image = img.convert("RGB")
        result = localize(
            image,
            strategy,
            is_document=is_document,
            template=templates.get(variant) if variant else None,
            raw_corners=raw_corners,
            rules=rules,
            excluded=excluded,
        )
        base = out / strategy / rel
        save_jpeg(redact(image, result.boxes), base.with_suffix(".jpg"))
        record = {
            "image": str(rel.with_suffix(".jpg")),
            "source_image": image_path.name,
            "strategy": strategy,
            "corners": corners,
            "is_document": is_document,
            "fallback": result.fallback,
            "boxes": [b.to_json() for b in result.boxes],
        }
        box_path = base.with_name(base.name + ".boxes.json")
        box_path.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        written.append(box_path)
    return written


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="python -m validation.localize",
        description="Localize sensitive fields and write solid-fill redacted copies.",
    )
    parser.add_argument("dataset", type=Path, help="folder of images with JSON labels")
    parser.add_argument("--strategy", required=True, choices=STRATEGIES)
    parser.add_argument("--out", type=Path, default=Path("data/localize"))
    parser.add_argument(
        "--corners",
        choices=("gt", "model"),
        default="gt",
        help="card corners from ground-truth labels, or from D8 detector output",
    )
    parser.add_argument(
        "--model-corners",
        type=Path,
        help="folder of detector JSON files mirroring the dataset layout (--corners model)",
    )
    args = parser.parse_args(argv)
    if args.corners == "model" and args.model_corners is None:
        parser.error("--corners model needs --model-corners")
    if not args.dataset.is_dir():
        parser.error(f"dataset folder not found: {args.dataset}")
    written = run(args.dataset, args.strategy, args.out, args.corners, args.model_corners)
    print(f"Wrote {len(written)} redacted images under {args.out / args.strategy}")


if __name__ == "__main__":
    try:
        main()
    except FileNotFoundError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
