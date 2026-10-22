"""Metadata specification and serialization for YOLO11n-pose LiteRT models.

Stores class names, input resolution, keypoint ordering, and quantization format
so the Android runtime (com.google.ai.edge.litert) can interpret model outputs
without hardcoded magic values.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

# Default card corner keypoints ordered clockwise from top-left.
# Clockwise ordering ensures standard homography mapping to template card dimensions.
DEFAULT_KEYPOINT_ORDER: list[str] = [
    "top_left",
    "top_right",
    "bottom_right",
    "bottom_left",
]

# Canonical document classes detected by Secure Gallery (§8.1, §8.2)
DEFAULT_DOCUMENT_CLASSES: list[str] = [
    "philsys",
    "lto",
    "payment_card",
]

DEFAULT_INPUT_SIZE: list[int] = [640, 640]


def build_model_metadata(
    class_names: list[str] | None = None,
    input_size: list[int] | tuple[int, int] | None = None,
    keypoint_order: list[str] | None = None,
    quantization: str = "fp16",
    model_name: str = "yolo11n-pose",
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Construct a complete model metadata dictionary for LiteRT deployment.

    Args:
        class_names: Ordered list of target class names.
        input_size: Expected model input dimensions [width, height].
        keypoint_order: Semantic names of the ordered keypoints.
        quantization: Model weight format ('fp16', 'int8', 'fp32').
        model_name: Descriptive architecture identifier.
        extra: Additional custom metadata fields.

    Returns:
        Structured metadata dictionary conforming to app consumption spec.
    """
    classes = list(class_names) if class_names is not None else list(DEFAULT_DOCUMENT_CLASSES)
    size = list(input_size) if input_size is not None else list(DEFAULT_INPUT_SIZE)
    kpts = list(keypoint_order) if keypoint_order is not None else list(DEFAULT_KEYPOINT_ORDER)

    metadata: dict[str, Any] = {
        "format": "secure-gallery-yolo-pose-litert",
        "model_name": model_name,
        "class_names": classes,
        "num_classes": len(classes),
        "input_size": size,
        "keypoint_order": kpts,
        "num_keypoints": len(kpts),
        "kpt_shape": [len(kpts), 2],
        "quantization": quantization.lower(),
    }
    if extra:
        metadata.update(extra)
    return metadata


def save_model_metadata(metadata: dict[str, Any], out_path: str | Path) -> Path:
    """Save model metadata dictionary to formatted JSON on disk.

    Args:
        metadata: Metadata dictionary to write.
        out_path: Destination file path.

    Returns:
        Resolved Path to written file.
    """
    p = Path(out_path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2, sort_keys=True)
    return p


def load_model_metadata(path: str | Path) -> dict[str, Any]:
    """Load and validate model metadata JSON from disk.

    Args:
        path: Path to metadata JSON file.

    Returns:
        Loaded metadata dictionary.

    Raises:
        FileNotFoundError: If metadata file does not exist.
        ValueError: If essential fields are missing from JSON.
    """
    p = Path(path)
    if not p.is_file():
        raise FileNotFoundError(f"Metadata file not found: {p}")
    with open(p, encoding="utf-8") as f:
        data: dict[str, Any] = json.load(f)

    required_keys = {"class_names", "input_size", "keypoint_order"}
    missing = required_keys - set(data.keys())
    if missing:
        raise ValueError(f"Invalid model metadata JSON, missing keys: {missing}")

    return data
