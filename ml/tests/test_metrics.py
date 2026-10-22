"""Unit tests for corner keypoint error, IoU, and classification metrics."""

import pytest

from ml.metrics import (
    compute_ap_from_pr,
    compute_classification_metrics,
    compute_corner_error,
    compute_iou,
)


def test_compute_corner_error_exact_match():
    corners = [[100, 100], [500, 100], [500, 300], [100, 300]]
    res = compute_corner_error(corners, corners, image_size=[1000, 1000])
    assert res["mean_corner_error"] == 0.0
    assert res["max_corner_error"] == 0.0
    for name in ["top_left", "top_right", "bottom_right", "bottom_left"]:
        assert res["per_corner"][name] == 0.0


def test_compute_corner_error_known_offset():
    gt = [[100.0, 100.0], [500.0, 100.0], [500.0, 300.0], [100.0, 300.0]]
    # Shift top_left by dx=3, dy=4 (dist=5)
    pred = [[103.0, 104.0], [500.0, 100.0], [500.0, 300.0], [100.0, 300.0]]
    res = compute_corner_error(pred, gt)
    assert pytest.approx(res["per_corner"]["top_left"]) == 5.0
    assert pytest.approx(res["mean_corner_error"]) == 5.0 / 4.0
    assert pytest.approx(res["max_corner_error"]) == 5.0


def test_compute_corner_error_invalid_length():
    with pytest.raises(ValueError, match="Exactly 4 corners"):
        compute_corner_error([[0, 0]], [[0, 0]])


def test_compute_iou():
    box1 = (0.5, 0.5, 0.4, 0.4)
    # Identical
    assert pytest.approx(compute_iou(box1, box1)) == 1.0

    # Completely disjoint
    box2 = (0.1, 0.1, 0.1, 0.1)
    assert compute_iou(box1, box2) == 0.0

    # Overlapping
    # box1: [0.3, 0.7] x [0.3, 0.7], area = 0.16
    # box3: [0.5, 0.9] x [0.3, 0.7], area = 0.16
    # intersection: [0.5, 0.7] x [0.3, 0.7], inter_area = 0.2 * 0.4 = 0.08
    # union: 0.16 + 0.16 - 0.08 = 0.24
    # iou: 0.08 / 0.24 = 1/3
    box3 = (0.7, 0.5, 0.4, 0.4)
    assert pytest.approx(compute_iou(box1, box3)) == 1.0 / 3.0


def test_compute_classification_metrics():
    # Regular case
    m = compute_classification_metrics(tp=8, fp=2, fn=2)
    assert pytest.approx(m["precision"]) == 0.8
    assert pytest.approx(m["recall"]) == 0.8
    assert pytest.approx(m["f1"]) == 0.8

    # Zero cases (no crash)
    z = compute_classification_metrics(tp=0, fp=0, fn=0)
    assert z["precision"] == 0.0
    assert z["recall"] == 0.0
    assert z["f1"] == 0.0


def test_compute_ap_from_pr():
    # Perfect curve: precision 1.0 at all recalls
    ap = compute_ap_from_pr([1.0, 1.0], [0.5, 1.0])
    assert pytest.approx(ap) == 1.0

    # Empty
    assert compute_ap_from_pr([], []) == 0.0
