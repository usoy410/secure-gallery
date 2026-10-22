"""Evaluation metrics for YOLO11n-pose document and corner detection.

Computes:
1. Corner keypoint distance errors (Euclidean pixel and normalized error).
2. Per-class Precision, Recall, and F1 score at IoU thresholds.
3. Class-level and mean Average Precision (mAP).
"""

from __future__ import annotations

import math
from typing import Any

from ml.metadata import DEFAULT_KEYPOINT_ORDER


def compute_corner_error(
    pred_corners: list[list[float] | tuple[float, float]],
    gt_corners: list[list[float] | tuple[float, float]],
    image_size: tuple[int, int] | list[int] | None = None,
) -> dict[str, Any]:
    """Compute Euclidean distance errors between predicted and ground truth corners.

    Corners are assumed in standard order: TL, TR, BR, BL.

    Args:
        pred_corners: 4 predicted (x, y) coordinates.
        gt_corners: 4 ground truth (x, y) coordinates.
        image_size: Optional [width, height] to scale normalized coordinates to pixels.

    Returns:
        Dictionary containing mean_corner_error, per_corner_errors, and max_corner_error.
    """
    if len(pred_corners) != 4 or len(gt_corners) != 4:
        raise ValueError("Exactly 4 corners required for card error calculation")

    scale_x = float(image_size[0]) if image_size else 1.0
    scale_y = float(image_size[1]) if image_size else 1.0

    errors: dict[str, float] = {}
    error_list: list[float] = []

    for idx, name in enumerate(DEFAULT_KEYPOINT_ORDER):
        px, py = float(pred_corners[idx][0]) * scale_x, float(pred_corners[idx][1]) * scale_y
        gx, gy = float(gt_corners[idx][0]) * scale_x, float(gt_corners[idx][1]) * scale_y
        dist = math.hypot(px - gx, py - gy)
        errors[name] = dist
        error_list.append(dist)

    mean_err = sum(error_list) / len(error_list)
    return {
        "mean_corner_error": mean_err,
        "max_corner_error": max(error_list),
        "per_corner": errors,
        "is_pixel": image_size is not None,
    }


def compute_iou(
    box1: tuple[float, float, float, float] | list[float],
    box2: tuple[float, float, float, float] | list[float],
) -> float:
    """Compute Intersection over Union (IoU) between two bounding boxes (cx, cy, w, h)."""
    cx1, cy1, w1, h1 = box1
    cx2, cy2, w2, h2 = box2

    x1_min, x1_max = cx1 - w1 / 2.0, cx1 + w1 / 2.0
    y1_min, y1_max = cy1 - h1 / 2.0, cy1 + h1 / 2.0
    x2_min, x2_max = cx2 - w2 / 2.0, cx2 + w2 / 2.0
    y2_min, y2_max = cy2 - h2 / 2.0, cy2 + h2 / 2.0

    inter_xmin = max(x1_min, x2_min)
    inter_ymin = max(y1_min, y2_min)
    inter_xmax = min(x1_max, x2_max)
    inter_ymax = min(y1_max, y2_max)

    inter_w = max(0.0, inter_xmax - inter_xmin)
    inter_h = max(0.0, inter_ymax - inter_ymin)
    inter_area = inter_w * inter_h

    area1 = max(0.0, w1) * max(0.0, h1)
    area2 = max(0.0, w2) * max(0.0, h2)
    union_area = area1 + area2 - inter_area

    if union_area <= 0:
        return 0.0
    return inter_area / union_area


def compute_classification_metrics(
    tp: int,
    fp: int,
    fn: int,
) -> dict[str, float]:
    """Compute Precision, Recall, and F1 score safely handling zero division."""
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * (precision * recall) / (precision + recall) if (precision + recall) > 0 else 0.0
    return {
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "tp": tp,
        "fp": fp,
        "fn": fn,
    }


def compute_ap_from_pr(precisions: list[float], recalls: list[float]) -> float:
    """Compute Area Under Curve / Average Precision given sorted PR pairs."""
    if not precisions or not recalls:
        return 0.0
    mrec = [0.0] + list(recalls) + [1.0]
    mpre = [0.0] + list(precisions) + [0.0]
    for i in range(len(mpre) - 2, -1, -1):
        mpre[i] = max(mpre[i], mpre[i + 1])
    ap = 0.0
    for i in range(len(mrec) - 1):
        ap += (mrec[i + 1] - mrec[i]) * mpre[i + 1]
    return ap
