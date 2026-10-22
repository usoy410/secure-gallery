"""Geometry and formatting utilities for YOLO pose format representations.

Handles bounding box calculation enclosing 4 card corners, keypoint normalization
in clockwise order (TL, TR, BR, BL), and license verification for external datasets.
"""

from __future__ import annotations

from pathlib import Path


def audit_roboflow_license(
    roboflow_id: str,
    licenses_path: str | Path = "ml/DATA_LICENSES.md",
) -> bool:
    """Verify that an external Roboflow dataset is formally recorded in DATA_LICENSES.md.

    Required by §10.1 and §20.1 item 4 before any external data may be ingested.

    Args:
        roboflow_id: Identifier or repository path of the dataset.
        licenses_path: Path to DATA_LICENSES.md registry.

    Returns:
        True if license is recorded.

    Raises:
        PermissionError: If dataset identifier is not found in the license registry.
    """
    p = Path(licenses_path)
    if not p.is_file():
        raise FileNotFoundError(f"License registry not found: {p}")
    content = p.read_text(encoding="utf-8")
    if roboflow_id not in content:
        raise PermissionError(
            f"Dataset '{roboflow_id}' is not recorded in {p}. "
            "External data must have verified license terms recorded before training use."
        )
    return True


def bounding_box_from_corners(
    corners: list[list[float | int]],
    image_size: tuple[int, int] | list[int],
) -> tuple[float, float, float, float]:
    """Compute normalized (x_center, y_center, width, height) enclosing the 4 card corners.

    Args:
        corners: List of 4 [x, y] corner coordinates.
        image_size: [width, height] of the image canvas.

    Returns:
        Normalized (cx, cy, w, h) clamped within [0.0, 1.0].
    """
    w_img, h_img = float(image_size[0]), float(image_size[1])
    if w_img <= 0 or h_img <= 0:
        raise ValueError(f"Invalid image dimensions: {image_size}")

    xs = [float(pt[0]) for pt in corners]
    ys = [float(pt[1]) for pt in corners]
    min_x = max(0.0, min(xs))
    max_x = min(w_img, max(xs))
    min_y = max(0.0, min(ys))
    max_y = min(h_img, max(ys))

    box_w = max(0.0, max_x - min_x)
    box_h = max(0.0, max_y - min_y)
    cx = min_x + box_w / 2.0
    cy = min_y + box_h / 2.0

    return (
        max(0.0, min(1.0, cx / w_img)),
        max(0.0, min(1.0, cy / h_img)),
        max(0.0, min(1.0, box_w / w_img)),
        max(0.0, min(1.0, box_h / h_img)),
    )


def ordered_corner_keypoints(
    corners: list[list[float | int]],
    image_size: tuple[int, int] | list[int],
    kpt_dim: int = 2,
) -> list[tuple[float, ...]]:
    """Normalize 4 card corner keypoints (TL, TR, BR, BL) to [0, 1].

    Args:
        corners: List of 4 [x, y] coordinates in clockwise order.
        image_size: [width, height] of the image.
        kpt_dim: 2 for (x, y) or 3 for (x, y, visibility).

    Returns:
        List of normalized keypoint tuples.
    """
    w_img, h_img = float(image_size[0]), float(image_size[1])
    normalized: list[tuple[float, ...]] = []
    for pt in corners:
        x, y = float(pt[0]), float(pt[1])
        nx = max(0.0, min(1.0, x / w_img))
        ny = max(0.0, min(1.0, y / h_img))
        if kpt_dim == 3:
            visible = 2.0 if (0 <= x <= w_img and 0 <= y <= h_img) else 1.0
            normalized.append((nx, ny, visible))
        else:
            normalized.append((nx, ny))
    return normalized


def format_yolo_pose_line(
    class_id: int,
    bbox: tuple[float, float, float, float],
    keypoints: list[tuple[float, ...]],
) -> str:
    """Format single line for YOLO pose label file.

    Format: <class_id> <cx> <cy> <w> <h> <k1_x> <k1_y> ...
    """
    cx, cy, w, h = bbox
    parts = [str(class_id), f"{cx:.6f}", f"{cy:.6f}", f"{w:.6f}", f"{h:.6f}"]
    for kpt in keypoints:
        for val in kpt:
            if isinstance(val, float) and val.is_integer():
                parts.append(str(int(val)))
            else:
                parts.append(f"{val:.6f}")
    return " ".join(parts) + "\n"
