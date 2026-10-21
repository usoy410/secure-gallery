"""Unit tests for YOLO pose evaluation and corner error metric calculation."""

from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pytest
import yaml

from ml.eval import evaluate_corner_errors


def test_evaluate_corner_errors_with_mock_model(tmp_path: Path):
    ds_dir = tmp_path / "dataset"
    val_imgs = ds_dir / "images" / "val"
    val_lbls = ds_dir / "labels" / "val"
    val_imgs.mkdir(parents=True)
    val_lbls.mkdir(parents=True)

    # 1. Sample image and label
    img_path = val_imgs / "card_001.png"
    img_path.write_bytes(b"\x89PNG\r\n\x1a\nfake")

    # Format: class cx cy w h kx1 ky1 kx2 ky2 kx3 ky3 kx4 ky4
    lbl_content = "0 0.5 0.5 0.8 0.5 0.1 0.25 0.9 0.25 0.9 0.75 0.1 0.75\n"
    (val_lbls / "card_001.txt").write_text(lbl_content, encoding="utf-8")

    # dataset.yaml
    data_yaml = ds_dir / "dataset.yaml"
    with open(data_yaml, "w", encoding="utf-8") as f:
        yaml.safe_dump({
            "path": str(ds_dir),
            "val": "images/val",
            "names": {0: "philsys", 1: "lto", 2: "payment_card"},
        }, f)

    # Mock detection result with perfect keypoint match
    mock_result = MagicMock()
    mock_result.boxes = [MagicMock()]
    mock_result.orig_shape = (1000, 1000)

    mock_xyn_item = MagicMock()
    mock_xyn_item.cpu().numpy.return_value = np.array([
        [0.1, 0.25], [0.9, 0.25], [0.9, 0.75], [0.1, 0.75]
    ])
    mock_kpts = MagicMock()
    mock_kpts.xyn = [mock_xyn_item]
    mock_result.keypoints = mock_kpts

    mock_model = MagicMock(return_value=[mock_result])

    report = evaluate_corner_errors(mock_model, data_yaml, split="val")

    assert report["sample_count"] == 1
    assert pytest.approx(report["mean_corner_error_px"]) == 0.0
    assert "philsys" in report["per_class"]
    assert pytest.approx(report["per_class"]["philsys"]) == 0.0
