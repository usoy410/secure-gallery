"""Dataset converter from Secure Gallery D7 benchmark format to Ultralytics YOLO pose format.

Converts card annotations (axis-aligned bounding box + 4 ordered corner keypoints)
into YOLO pose text labels. Supports:
1. Canonical document classes (philsys, drivers_license, payment_card) or design variants.
2. Background / negative images represented as empty label files for false-positive suppression.
3. Audited Roboflow external training data added strictly to the 'train' split (§10.1).
"""

from __future__ import annotations

import argparse
import csv
import json
import logging
import shutil
from pathlib import Path
from typing import Any

import yaml

from ml.metadata import DEFAULT_DOCUMENT_CLASSES, build_model_metadata, save_model_metadata
from ml.pose_format import (
    audit_roboflow_license,
    bounding_box_from_corners,
    format_yolo_pose_line,
    ordered_corner_keypoints,
)

logger = logging.getLogger(__name__)


CLASS_ALIASES: dict[str, str] = {
    "drivers_license": "lto",
    "driver_license": "lto",
    "philsys_id": "philsys",
    "payment_cards": "payment_card",
}


def convert_single_sample(
    label_path: Path,
    image_path: Path,
    dest_labels_dir: Path,
    dest_images_dir: Path,
    class_to_id: dict[str, int],
    class_mode: str = "document_class",
    kpt_dim: int = 2,
    copy_image: bool = True,
) -> bool:
    """Convert a single label JSON and image into YOLO pose format files.

    Returns:
        True if sample was converted successfully.
    """
    with open(label_path, encoding="utf-8") as f:
        data: dict[str, Any] = json.load(f)

    dest_images_dir.mkdir(parents=True, exist_ok=True)
    dest_labels_dir.mkdir(parents=True, exist_ok=True)

    dest_img = dest_images_dir / image_path.name
    dest_lbl = dest_labels_dir / f"{image_path.stem}.txt"

    if copy_image and not dest_img.exists():
        shutil.copy2(image_path, dest_img)

    corners = data.get("card_corners", [])
    is_neg = bool(data.get("is_negative", False)) or len(corners) < 4

    # Negative images have no detections (empty label file)
    if is_neg:
        dest_lbl.write_text("", encoding="utf-8")
        return True

    if class_mode == "document_class":
        target_class_key = data.get("document_class", data.get("class"))
    else:
        target_class_key = data.get("variant", data.get("class"))

    if class_mode == "document_class" and target_class_key in CLASS_ALIASES:
        target_class_key = CLASS_ALIASES[target_class_key]
    if target_class_key not in class_to_id:
        logger.warning(
            "Skipping sample %s with unknown class '%s'", label_path.name, target_class_key
        )
        return False

    class_id = class_to_id[target_class_key]
    img_size = data.get("image_size", [1200, 800])
    bbox = bounding_box_from_corners(corners, img_size)
    kpts = ordered_corner_keypoints(corners, img_size, kpt_dim=kpt_dim)
    line = format_yolo_pose_line(class_id, bbox, kpts)
    dest_lbl.write_text(line, encoding="utf-8")
    return True


def convert_d7_dataset(
    d7_dir: str | Path,
    output_dir: str | Path,
    class_mode: str = "document_class",
    roboflow_dir: str | Path | None = None,
    roboflow_id: str | None = None,
    licenses_path: str | Path = "ml/DATA_LICENSES.md",
    kpt_dim: int = 2,
    copy_images: bool = True,
) -> dict[str, Any]:
    """Execute complete conversion of a D7 dataset directory to YOLO pose structure."""
    src = Path(d7_dir)
    out = Path(output_dir)
    manifest_p = src / "manifest.csv"
    if not manifest_p.is_file():
        raise FileNotFoundError(f"D7 manifest.csv not found at {manifest_p}")

    class_names = list(DEFAULT_DOCUMENT_CLASSES)
    if class_mode == "variant":
        # Discover variants dynamically from manifest
        with open(manifest_p, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            class_names = sorted(
                list({r["variant"] for r in reader if r.get("is_negative") != "True"})
            )
    class_to_id = {cls_name: idx for idx, cls_name in enumerate(class_names)}

    counts: dict[str, int] = {"train": 0, "val": 0, "test": 0}
    with open(manifest_p, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            split = row.get("split", "train")
            img_rel = row["image_path"]
            lbl_rel = row["label_path"]
            img_path = src / img_rel
            lbl_path = src / lbl_rel
            if not img_path.is_file() or not lbl_path.is_file():
                continue
            dest_img_dir = out / "images" / split
            dest_lbl_dir = out / "labels" / split
            ok = convert_single_sample(
                lbl_path, img_path, dest_lbl_dir, dest_img_dir,
                class_to_id, class_mode=class_mode, kpt_dim=kpt_dim, copy_image=copy_images
            )
            if ok:
                counts[split] = counts.get(split, 0) + 1

    # Ingest Roboflow data if requested (strictly into train split)
    if roboflow_dir:
        rf_path = Path(roboflow_dir)
        rf_id = roboflow_id or rf_path.name
        audit_roboflow_license(rf_id, licenses_path=licenses_path)
        rf_train_imgs = out / "images" / "train"
        rf_train_lbls = out / "labels" / "train"
        for rf_lbl in rf_path.glob("**/*.txt"):
            rf_img = rf_lbl.with_suffix(".png")
            if not rf_img.is_file():
                rf_img = rf_lbl.with_suffix(".jpg")
            if rf_img.is_file():
                dest_name = f"rf_{rf_lbl.stem}"
                shutil.copy2(rf_img, rf_train_imgs / f"{dest_name}{rf_img.suffix}")
                shutil.copy2(rf_lbl, rf_train_lbls / f"{dest_name}.txt")
                counts["train"] = counts.get("train", 0) + 1

    # Write Ultralytics YOLO pose dataset.yaml
    data_yaml: dict[str, Any] = {
        "path": str(out.resolve()),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "kpt_shape": [4, kpt_dim],
        "names": {idx: name for idx, name in enumerate(class_names)},
    }
    yaml_path = out / "dataset.yaml"
    with open(yaml_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(data_yaml, f, sort_keys=False)

    metadata = build_model_metadata(
        class_names=class_names, extra={"class_mode": class_mode, "kpt_dim": kpt_dim}
    )
    save_model_metadata(metadata, out / "metadata.json")

    return {
        "counts": counts,
        "classes": class_names,
        "yaml_path": str(yaml_path),
        "metadata_path": str(out / "metadata.json"),
    }


def main() -> None:
    """CLI entry point for dataset conversion."""
    parser = argparse.ArgumentParser(description="Convert D7 dataset to YOLO pose format.")
    parser.add_argument(
        "--input", "-i", required=True, help="Path to generated D7 benchmark dataset"
    )
    parser.add_argument(
        "--output", "-o", required=True, help="Destination directory for YOLO pose dataset"
    )
    parser.add_argument(
        "--class-mode", choices=["document_class", "variant"], default="document_class"
    )
    parser.add_argument(
        "--roboflow-dir", default=None, help="Optional external Roboflow dataset directory"
    )
    parser.add_argument(
        "--roboflow-id", default=None, help="Identifier of Roboflow dataset in DATA_LICENSES.md"
    )
    parser.add_argument(
        "--kpt-dim",
        type=int,
        choices=[2, 3],
        default=2,
        help="Keypoint dimension (2 for xy, 3 for xyv)",
    )
    args = parser.parse_args()

    result = convert_d7_dataset(
        args.input,
        args.output,
        class_mode=args.class_mode,
        roboflow_dir=args.roboflow_dir,
        roboflow_id=args.roboflow_id,
        kpt_dim=args.kpt_dim,
    )
    print(
        f"Dataset conversion complete: {result['counts']} samples converted. "
        f"YAML: {result['yaml_path']}"
    )


if __name__ == "__main__":
    main()
