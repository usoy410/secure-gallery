"""Training pipeline for YOLO11n-pose on Secure Gallery D7 benchmark dataset.

Provides training orchestration with:
1. Target 640 px resolution for on-device detection (§8.2, §8.6).
2. --smoke mode: 1 epoch on 50 images in under 10 minutes on CPU.
3. Automated post-training LiteRT export (INT8 and float precision) with metadata JSON.
"""

from __future__ import annotations

import argparse
import logging
import time
from pathlib import Path
from typing import Any

from ml.converter import convert_d7_dataset
from ml.dataset import build_full_dataset
from ml.export import export_litert_models

logger = logging.getLogger(__name__)


def prepare_smoke_dataset(base_dir: str | Path = "data/smoke") -> Path:
    """Generate and convert a compact 50-sample dataset specifically for smoke testing.

    Args:
        base_dir: Base directory to store generated smoke assets.

    Returns:
        Path to converted YOLO pose dataset.yaml.
    """
    base = Path(base_dir)
    d7_dir = base / "d7"
    yolo_dir = base / "yolo"

    yaml_target = yolo_dir / "dataset.yaml"
    if yaml_target.is_file():
        return yaml_target

    # Configure a tiny 50-image dataset (35 train / 15 val)
    smoke_config: dict[str, Any] = {
        "dataset": {
            "name": "Secure Gallery Smoke Dataset",
            "out_dir": str(d7_dir),
            "seed": 42,
            "splits": {"train": 0.70, "val": 0.30, "test": 0.0},
        },
        "cards": {
            "variants": [
                "philsys-card",
                "philsys-ephilid",
                "lto-dl-current",
                "lto-dl-previous",
                "payment-card-front",
                "payment-card-back",
            ],
            "count_per_variant": 7,
            "composite": True,
            "jitter": True,
            "conditions": [],
        },
        "negatives": {
            "enabled": True,
            "count_per_type": 2,
            "types": ["receipt", "loyalty_card", "blank_card", "screenshot"],
            "photos_dir": "data/photos",
        },
        "failed_redactions": {"enabled": False},
    }

    build_full_dataset(smoke_config)
    res = convert_d7_dataset(d7_dir, yolo_dir, class_mode="document_class")
    return Path(res["yaml_path"])


def train_yolo_pose(
    data_yaml: str | Path,
    epochs: int = 50,
    imgsz: int = 640,
    batch: int = 8,
    device: str = "cpu",
    project: str = "runs/pose",
    name: str = "train",
    pretrained_weights: str = "yolo11n-pose.pt",
    export_after_training: bool = True,
    workers: int = 0,
) -> dict[str, Any]:
    """Train YOLO11n-pose model and optionally export LiteRT models."""
    from ultralytics import YOLO

    yaml_p = Path(data_yaml)
    if not yaml_p.is_file():
        raise FileNotFoundError(f"dataset.yaml not found: {yaml_p}")

    start_time = time.time()
    model = YOLO(pretrained_weights)

    train_args = {
        "data": str(yaml_p.resolve()),
        "epochs": epochs,
        "imgsz": imgsz,
        "batch": batch,
        "device": device,
        "project": project,
        "name": name,
        "workers": workers,
        "verbose": True,
    }

    train_results = model.train(**train_args)
    elapsed_sec = time.time() - start_time

    save_dir = Path(getattr(train_results, "save_dir", Path(project) / name))
    best_pt = save_dir / "weights" / "best.pt"
    if not best_pt.is_file():
        best_pt = save_dir / "weights" / "last.pt"

    output_info: dict[str, Any] = {
        "elapsed_seconds": round(elapsed_sec, 2),
        "weights_path": str(best_pt),
        "train_results": train_results,
    }

    if export_after_training and best_pt.is_file():
        export_out = save_dir / "export"
        export_info = export_litert_models(
            best_pt,
            output_dir=export_out,
            dataset_yaml=yaml_p,
            imgsz=imgsz,
        )
        output_info["export"] = export_info

    return output_info


def main() -> None:
    """CLI entry point for python -m ml.train."""
    parser = argparse.ArgumentParser(description="Train YOLO11n-pose for Secure Gallery.")
    parser.add_argument(
        "--smoke", action="store_true", help="Run 1 epoch on 50 images in under 10 mins on CPU"
    )
    parser.add_argument("--data", default=None, help="Path to YOLO pose dataset.yaml")
    parser.add_argument("--epochs", type=int, default=None, help="Number of training epochs")
    parser.add_argument("--imgsz", type=int, default=640, help="Image resolution")
    parser.add_argument("--batch", type=int, default=None, help="Batch size")
    parser.add_argument("--device", default="cpu", help="Device (cpu or cuda)")
    parser.add_argument("--no-export", action="store_true", help="Skip LiteRT export step")
    parser.add_argument(
        "--workers",
        type=int,
        default=2,
        help="Data loader processes; 0 makes the GPU wait on one CPU thread (Colab has 2 CPUs)",
    )
    args = parser.parse_args()

    if args.smoke:
        data_yaml = args.data or prepare_smoke_dataset("data/smoke")
        epochs = args.epochs or 1
        batch = args.batch or 4
        exp_name = "smoke"
    else:
        data_yaml = args.data or "data/yolo_pose/dataset.yaml"
        epochs = args.epochs or 50
        batch = args.batch or 8
        exp_name = "train"

    res = train_yolo_pose(
        data_yaml,
        epochs=epochs,
        imgsz=args.imgsz,
        batch=batch,
        device=args.device,
        name=exp_name,
        export_after_training=not args.no_export,
        workers=args.workers,
    )
    print(f"Training completed in {res['elapsed_seconds']}s. Weights: {res['weights_path']}")


if __name__ == "__main__":
    main()
