"""Validation evaluation script for YOLO11n-pose document & keypoint detection.

Reports:
1. Per-class Precision, Recall, and F1 score.
2. mAP@50 and mAP@50:95.
3. Card corner distance error (mean Euclidean pixel and normalized error) on the validation split.
4. Corner error as a percent of the card width: each image's worst corner, summarised as
   median, 95th percentile and maximum. The app's picture margin is 3% of the card width
   (#54), so a corner further off than that can leave a strip of a face or signature
   uncovered (#18, #64). ``--max-corner-pct 3`` turns this into a pass/fail check: the 95th
   percentile on a split, and every photo with ``--photos``.

``--photos DIR`` runs the same measure on labeled photos (``<id>.jpg`` with ``<id>.json``
from the generator), such as the emulator's redaction fixtures.
"""

from __future__ import annotations

import argparse
import json
import logging
import math
import sys
from pathlib import Path
from typing import Any

from ml.metrics import compute_corner_error

logger = logging.getLogger(__name__)

Point = tuple[float, float]


def corner_error_pct(pred: list[Point], true: list[Point]) -> float:
    """The worst corner's distance as a percent of the card width.

    Corners are TL, TR, BR, BL in pixels. Card width is the mean of the top and bottom edges.
    """
    width = (math.dist(true[0], true[1]) + math.dist(true[3], true[2])) / 2
    return 100.0 * max(math.dist(p, t) for p, t in zip(pred, true, strict=True)) / width


def summarize_pct(values: list[float]) -> dict[str, float | int]:
    """Median, 95th percentile (nearest rank) and maximum of per-image errors."""
    if not values:
        return {"count": 0, "median_pct": 0.0, "p95_pct": 0.0, "max_pct": 0.0}
    ordered = sorted(values)
    p95 = ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]
    return {
        "count": len(ordered),
        "median_pct": round(ordered[(len(ordered) - 1) // 2], 2),
        "p95_pct": round(p95, 2),
        "max_pct": round(ordered[-1], 2),
    }


def _best_corners(result: Any) -> list[Point] | None:
    """Pixel corners of the most confident detection, or None if there is none."""
    if result.keypoints is None or len(result.boxes) == 0:
        return None
    confs = [float(c) for c in result.boxes.conf]
    best = confs.index(max(confs))
    kpts = result.keypoints.xy[best]
    if len(kpts) < 4:
        return None
    return [(float(kpts[i][0]), float(kpts[i][1])) for i in range(4)]


def evaluate_photos(model: Any, photos_dir: Path, conf: float = 0.25) -> dict[str, Any]:
    """Corner error on labeled photos, read upright (EXIF rotation applied) like the app."""
    from PIL import Image, ImageOps

    errors: list[float] = []
    missed = 0
    for label_path in sorted(photos_dir.glob("*.json")):
        if label_path.name.endswith(".boxes.json"):
            continue
        image_path = label_path.with_suffix(".jpg")
        if not image_path.exists():
            continue
        label = json.loads(label_path.read_text(encoding="utf-8"))
        true = [(float(x), float(y)) for x, y in label["card_corners"]]
        with Image.open(image_path) as raw:
            image = ImageOps.exif_transpose(raw).convert("RGB")
        preds = model(image, conf=conf, verbose=False)
        corners = _best_corners(preds[0]) if preds else None
        if corners is None:
            missed += 1
            continue
        errors.append(corner_error_pct(corners, true))
    return {"photos": len(errors) + missed, "missed": missed, **summarize_pct(errors)}


def evaluate_corner_errors(
    model: Any,
    dataset_yaml_path: Path,
    split: str = "val",
    conf: float = 0.25,
) -> dict[str, Any]:
    """Compute Euclidean card-corner distance errors on the specified dataset split."""
    import yaml

    with open(dataset_yaml_path, encoding="utf-8") as f:
        cfg = yaml.safe_load(f)

    ds_root = Path(cfg.get("path", dataset_yaml_path.parent))
    split_imgs_dir = ds_root / cfg.get(split, f"images/{split}")
    split_lbls_dir = ds_root / cfg.get("labels", f"labels/{split}")
    if not split_lbls_dir.exists():
        split_lbls_dir = ds_root / "labels" / split

    class_names = cfg.get("names", {})
    if isinstance(class_names, dict):
        class_names = [class_names[k] for k in sorted(class_names.keys())]

    img_files = list(split_imgs_dir.glob("*.png")) + list(split_imgs_dir.glob("*.jpg"))
    if not img_files:
        return {"mean_corner_error_px": 0.0, "per_class": {}}

    class_errors: dict[str, list[float]] = {cls_name: [] for cls_name in class_names}
    all_errors: list[float] = []
    pct_errors: list[float] = []

    for img_p in img_files:
        lbl_p = split_lbls_dir / f"{img_p.stem}.txt"
        if not lbl_p.is_file():
            continue
        lbl_lines = lbl_p.read_text(encoding="utf-8").strip().splitlines()
        if not lbl_lines or not lbl_lines[0].strip():
            continue  # negative background sample

        parts = lbl_lines[0].split()
        cls_idx = int(parts[0])
        cls_name = class_names[cls_idx] if cls_idx < len(class_names) else str(cls_idx)

        # Ground truth keypoints
        gt_kpts: list[tuple[float, float]] = []
        raw_kpts = parts[5:]
        step = 3 if len(raw_kpts) >= 12 else 2
        for i in range(0, min(len(raw_kpts), 4 * step), step):
            gt_kpts.append((float(raw_kpts[i]), float(raw_kpts[i + 1])))

        if len(gt_kpts) != 4:
            continue

        preds = model(str(img_p), conf=conf, verbose=False)
        if not preds or len(preds[0].boxes) == 0 or preds[0].keypoints is None:
            continue

        # Get first detection's keypoints normalized
        kpts_data = preds[0].keypoints.xyn[0].cpu().numpy()
        if len(kpts_data) < 4:
            continue

        pred_corners = [(float(kpts_data[i][0]), float(kpts_data[i][1])) for i in range(4)]
        w_img, h_img = preds[0].orig_shape[1], preds[0].orig_shape[0]

        err_info = compute_corner_error(pred_corners, gt_kpts, image_size=(w_img, h_img))
        all_errors.append(err_info["mean_corner_error"])
        pct_errors.append(
            corner_error_pct(
                [(x * w_img, y * h_img) for x, y in pred_corners],
                [(x * w_img, y * h_img) for x, y in gt_kpts],
            )
        )
        class_errors[cls_name].append(err_info["mean_corner_error"])

    per_class_summary = {
        cls_name: round(sum(errs) / len(errs), 2) if errs else 0.0
        for cls_name, errs in class_errors.items()
    }
    overall_mean = round(sum(all_errors) / len(all_errors), 2) if all_errors else 0.0

    return {
        "mean_corner_error_px": overall_mean,
        "per_class": per_class_summary,
        "sample_count": len(all_errors),
        "corner_error_pct": summarize_pct(pct_errors),
    }


def evaluate_yolo_pose(
    model_path: str | Path,
    data_yaml: str | Path,
    split: str = "val",
    imgsz: int = 640,
    conf: float = 0.25,
) -> dict[str, Any]:
    """Run full evaluation on val split producing P, R, F1, mAP, and corner errors."""
    from ultralytics import YOLO

    model = YOLO(str(model_path))
    yaml_p = Path(data_yaml)

    # 1. Ultralytics validation run
    val_metrics = model.val(
        data=str(yaml_p.resolve()),
        split=split,
        imgsz=imgsz,
        conf=conf,
        verbose=False,
    )

    # 2. Card corner error evaluation
    corner_errs = evaluate_corner_errors(model, yaml_p, split=split, conf=conf)

    # 3. Extract per-class P, R, F1, mAP
    names = getattr(model, "names", {})
    if isinstance(names, dict):
        class_list = [names[k] for k in sorted(names.keys())]
    else:
        class_list = list(names)

    per_class_metrics: dict[str, dict[str, float]] = {}
    box_p = getattr(val_metrics.box, "p", [])
    box_r = getattr(val_metrics.box, "r", [])
    box_f1 = getattr(val_metrics.box, "f1", [])
    box_ap50 = getattr(val_metrics.box, "all_ap", None)

    for idx, cname in enumerate(class_list):
        p = float(box_p[idx]) if idx < len(box_p) else 0.0
        r = float(box_r[idx]) if idx < len(box_r) else 0.0
        f1 = float(box_f1[idx]) if idx < len(box_f1) else 0.0
        ap = float(box_ap50[idx][0]) if box_ap50 is not None and idx < len(box_ap50) else 0.0
        per_class_metrics[cname] = {
            "precision": round(p, 4),
            "recall": round(r, 4),
            "f1": round(f1, 4),
            "map50": round(ap, 4),
            "corner_error_px": corner_errs["per_class"].get(cname, 0.0),
        }

    report = {
        "split": split,
        "overall": {
            "map50": round(float(val_metrics.box.map50), 4),
            "map50_95": round(float(val_metrics.box.map), 4),
            "mean_corner_error_px": corner_errs["mean_corner_error_px"],
            "corner_error_pct": corner_errs.get("corner_error_pct", summarize_pct([])),
        },
        "per_class": per_class_metrics,
    }

    print("\n" + "=" * 65)
    print(f"EVALUATION RESULTS (Split: {split})")
    print("=" * 65)
    print(f"{'Class':<20} {'Precision':<10} {'Recall':<10} {'F1':<10} {'Corner Err(px)':<15}")
    print("-" * 65)
    for cname, m in per_class_metrics.items():
        print(
            f"{cname:<20} {m['precision']:<10.3f} {m['recall']:<10.3f} "
            f"{m['f1']:<10.3f} {m['corner_error_px']:<15.2f}"
        )
    print("-" * 65)
    print(
        f"Overall mAP50: {report['overall']['map50']:.4f} | "
        f"mAP50-95: {report['overall']['map50_95']:.4f} | "
        f"Mean Corner Err: {report['overall']['mean_corner_error_px']:.2f} px"
    )
    pct = report["overall"]["corner_error_pct"]
    print(
        f"Corner error (% of card width): median {pct['median_pct']:.2f} | "
        f"p95 {pct['p95_pct']:.2f} | max {pct['max_pct']:.2f}"
    )
    print("=" * 65 + "\n")

    return report


def main(argv: list[str] | None = None) -> None:
    """CLI entry point for python -m ml.eval."""
    parser = argparse.ArgumentParser(description="Evaluate YOLO11n-pose detector.")
    parser.add_argument("--model", "-m", required=True, help="Path to .pt model weights")
    parser.add_argument("--data", "-d", default=None, help="Path to dataset.yaml")
    parser.add_argument("--split", default="val", help="Evaluation split (val or test)")
    parser.add_argument("--imgsz", type=int, default=640, help="Image resolution")
    parser.add_argument("--save-json", default=None, help="Optional path to save metrics JSON")
    parser.add_argument(
        "--photos", default=None, help="Folder of labeled photos to measure corner error on"
    )
    parser.add_argument(
        "--max-corner-pct",
        type=float,
        default=None,
        help="Fail (exit 1) if the corner error is above this percent of the card width: the "
        "95th percentile on the split, and every photo (the maximum) with --photos, where a "
        "photo with no detection also fails",
    )
    args = parser.parse_args(argv)
    if args.data is None and args.photos is None:
        parser.error("give --data, --photos or both")

    results: dict[str, Any] = {}
    checks: list[tuple[str, float, int]] = []  # name, error in % of card width, misses
    if args.data:
        results = evaluate_yolo_pose(args.model, args.data, split=args.split, imgsz=args.imgsz)
        split_p95 = results["overall"]["corner_error_pct"]["p95_pct"]
        checks.append((f"{args.split} split p95", split_p95, 0))
    if args.photos:
        from ultralytics import YOLO

        photos = evaluate_photos(YOLO(str(args.model)), Path(args.photos))
        results["photos"] = photos
        # The device leak test judges every photo, so every photo must be within the limit.
        checks.append((f"photos in {args.photos} (max)", photos["max_pct"], photos["missed"]))
        print(
            f"Photos: {photos['photos']} ({photos['missed']} missed) | corner error "
            f"median {photos['median_pct']:.2f}% | p95 {photos['p95_pct']:.2f}% | "
            f"max {photos['max_pct']:.2f}% of card width"
        )
    if args.save_json:
        with open(args.save_json, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, default=str)
    if args.max_corner_pct is not None:
        failed = [
            name for name, value, missed in checks if value > args.max_corner_pct or missed > 0
        ]
        if failed:
            print(f"Corner check FAILED (limit {args.max_corner_pct}%): {', '.join(failed)}")
            sys.exit(1)
        print(f"Corner check passed: within {args.max_corner_pct}% of the card width")


if __name__ == "__main__":
    main()
