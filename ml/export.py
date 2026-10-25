"""LiteRT (TFLite) export pipeline for YOLO11n-pose models.

Exports trained models to Google AI Edge LiteRT format (com.google.ai.edge.litert)
in INT8 and float/FP16 precision variants, measures model disk size against the
10 MB on-device constraint (§8.7), and bundles model metadata JSON.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path
from typing import Any

from ml.metadata import (
    DEFAULT_DOCUMENT_CLASSES,
    DEFAULT_KEYPOINT_ORDER,
    build_model_metadata,
    save_model_metadata,
)

TARGET_MAX_SIZE_MB = 10.0


def get_file_size_mb(path: str | Path) -> float:
    """Return size of a file in megabytes (MB)."""
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(f"Model file not found: {p}")
    return p.stat().st_size / (1024.0 * 1024.0)


def export_single_precision(
    model: Any,
    precision: str,
    dataset_yaml: str | Path | None = None,
    imgsz: int = 640,
) -> Path:
    """Export YOLO model to a specific LiteRT precision format.

    Note on Ultralytics 8.4+: 'format=tflite' maps to 'litert'.
    Supported quantizations: 8 (INT8), 'w8a16', None/32 (Float).
    """
    prec = precision.lower()
    export_kwargs: dict[str, Any] = {"imgsz": imgsz}

    # Attempt LiteRT export
    if prec in ("int8", "8"):
        export_kwargs["format"] = "litert"
        export_kwargs["quantize"] = 8
    elif prec in ("fp16", "half", "w8a16"):
        # Ultralytics LiteRT uses w8a16 or float32 (quantize=16 is unsupported in litert backend)
        export_kwargs["format"] = "litert"
        export_kwargs["quantize"] = "w8a16"
    else:
        export_kwargs["format"] = "litert"
        export_kwargs["quantize"] = None

    if dataset_yaml and export_kwargs["quantize"] in (8, "w8a16"):
        export_kwargs["data"] = str(dataset_yaml)

    # A failed quantized export must fail visibly, rather than shipping a float
    # retry under an INT8 filename and metadata label.
    exported = Path(model.export(**export_kwargs))
    if prec in ("int8", "8", "fp16", "half", "w8a16"):
        verify_int8_tensors(exported)
    return exported


def verify_int8_tensors(model_path: str | Path) -> int:
    """Reject float exports mislabeled as INT8 (float graph I/O is permitted)."""
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    quantized = sum(
        tensor["dtype"] == np.int8 and len(tensor["quantization_parameters"]["scales"]) > 0
        for tensor in interpreter.get_tensor_details()
    )
    if not quantized:
        raise ValueError(
            f"{model_path}: requested quantized export has no scaled INT8 tensors; "
            "refusing to label it as INT8. Check the export and calibration errors."
        )
    return quantized


def export_litert_models(
    model_source: str | Path | Any,
    output_dir: str | Path | None = None,
    dataset_yaml: str | Path | None = None,
    precisions: tuple[str, ...] = ("int8", "fp16"),
    imgsz: int = 640,
    target_max_mb: float = TARGET_MAX_SIZE_MB,
    class_names: list[str] | None = None,
) -> dict[str, Any]:
    """Export YOLO11n-pose model to INT8 and FP16/float LiteRT models with metadata.

    Args:
        model_source: Path to .pt model weights or loaded YOLO model object.
        output_dir: Destination directory for exported files.
        dataset_yaml: Path to dataset.yaml (required for INT8 calibration).
        precisions: Precisions to export ('int8', 'fp16').
        imgsz: Square input resolution for the model (default 640).
        target_max_mb: Target size ceiling in MB (default 10 MB per §8.7).
        class_names: List of class labels (defaults to standard 3 classes).

    Returns:
        Summary dict containing exported model paths, sizes in MB, and budget compliance.
    """
    try:
        from ultralytics import YOLO
    except ImportError as e:
        raise ImportError(
            "ultralytics is required for model export. Install with 'pip install -e .[train]'."
        ) from e

    model = model_source if not isinstance(model_source, str | Path) else YOLO(str(model_source))
    dest_dir = Path(output_dir) if output_dir else Path("runs/export")
    dest_dir.mkdir(parents=True, exist_ok=True)

    results: dict[str, Any] = {"models": {}, "metadata_path": "", "all_within_budget": True}
    names = class_names or getattr(model, "names", DEFAULT_DOCUMENT_CLASSES)
    if isinstance(names, dict):
        names = [names[k] for k in sorted(names.keys())]

    for prec in precisions:
        exported = export_single_precision(model, prec, dataset_yaml=dataset_yaml, imgsz=imgsz)
        dest_file = dest_dir / f"yolo11n_pose_{prec}.tflite"
        shutil.copy2(exported, dest_file)
        size_mb = get_file_size_mb(dest_file)
        within_budget = size_mb <= target_max_mb

        if not within_budget:
            results["all_within_budget"] = False

        results["models"][prec] = {
            "path": str(dest_file),
            "size_mb": round(size_mb, 2),
            "target_max_mb": target_max_mb,
            "within_budget": within_budget,
        }
        print(
            f"LiteRT {prec.upper()} model: {dest_file} "
            f"({size_mb:.2f} MB, target <= {target_max_mb} MB: {within_budget})"
        )

    # Generate and write metadata JSON
    meta = build_model_metadata(
        class_names=names,
        input_size=[imgsz, imgsz],
        keypoint_order=DEFAULT_KEYPOINT_ORDER,
        quantization=(
            "int8"
            if any(p in ("int8", "8") for p in precisions)
            else "w8a16"
            if any(p in ("fp16", "half", "w8a16") for p in precisions)
            else "fp32"
        ),
        extra={"target_budget_mb": target_max_mb},
    )
    meta_path = dest_dir / "metadata.json"
    save_model_metadata(meta, meta_path)
    results["metadata_path"] = str(meta_path)

    return results


def main() -> None:
    """CLI entry point for LiteRT export."""
    parser = argparse.ArgumentParser(description="Export YOLO11n-pose to LiteRT.")
    parser.add_argument("--model", "-m", required=True, help="Path to trained .pt weights")
    parser.add_argument("--output", "-o", default="runs/export", help="Destination folder")
    parser.add_argument("--data", default=None, help="Path to dataset.yaml for INT8 calibration")
    parser.add_argument("--imgsz", type=int, default=640, help="Input resolution")
    args = parser.parse_args()

    export_litert_models(
        args.model, output_dir=args.output, dataset_yaml=args.data, imgsz=args.imgsz
    )


if __name__ == "__main__":
    main()
