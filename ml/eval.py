"""Validation evaluation script for YOLO11n-pose document & keypoint detection.

Reports:
1. Per-class Precision, Recall, and F1 score.
2. mAP@50 and mAP@50:95.
3. Card corner distance error (mean Euclidean pixel and normalized error) on the validation split.
"""

from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path
from typing import Any

from ml.metrics import compute_corner_error

logger = logging.getLogger(__name__)


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
    print("=" * 65 + "\n")

    return report


def main() -> None:
    """CLI entry point for python -m ml.eval."""
    parser = argparse.ArgumentParser(description="Evaluate YOLO11n-pose detector.")
    parser.add_argument("--model", "-m", required=True, help="Path to .pt model weights")
    parser.add_argument("--data", "-d", required=True, help="Path to dataset.yaml")
    parser.add_argument("--split", default="val", help="Evaluation split (val or test)")
    parser.add_argument("--imgsz", type=int, default=640, help="Image resolution")
    parser.add_argument("--save-json", default=None, help="Optional path to save metrics JSON")
    args = parser.parse_args()

    results = evaluate_yolo_pose(args.model, args.data, split=args.split, imgsz=args.imgsz)
    if args.save_json:
        with open(args.save_json, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2)


if __name__ == "__main__":
    main()
