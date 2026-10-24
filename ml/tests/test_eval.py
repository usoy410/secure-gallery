"""Unit tests for YOLO pose evaluation and corner error metric calculation."""

import json
import sys
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


# --- corner error as a percent of the card width (#9, #64) -----------------------------


def test_corner_error_pct_uses_the_worst_corner_and_the_card_width() -> None:
    from ml.eval import corner_error_pct

    true = [(0.0, 0.0), (200.0, 0.0), (200.0, 120.0), (0.0, 120.0)]
    assert corner_error_pct(true, true) == 0.0
    off = [(6.0, 0.0), (200.0, 0.0), (200.0, 120.0), (0.0, 120.0)]
    assert corner_error_pct(off, true) == pytest.approx(3.0)


def test_summarize_pct_reports_median_p95_and_max() -> None:
    from ml.eval import summarize_pct

    stats = summarize_pct([float(v) for v in range(1, 21)])
    assert stats == {"count": 20, "median_pct": 10.0, "p95_pct": 19.0, "max_pct": 20.0}
    assert summarize_pct([])["count"] == 0


class _Boxes:
    def __init__(self, confs: list[float]) -> None:
        self.conf = confs

    def __len__(self) -> int:
        return len(self.conf)


class _Result:
    def __init__(self, detections: list[tuple[float, list[tuple[float, float]]]]) -> None:
        self.boxes = _Boxes([c for c, _ in detections])
        self.keypoints = (
            type("K", (), {"xy": [k for _, k in detections]})() if detections else None
        )


def _labeled_photo(folder: Path, name: str, corners: list[list[int]], rotated: bool) -> None:
    from PIL import Image

    img = Image.new("RGB", (400, 300), "white")
    exif = Image.Exif()
    if rotated:
        img = img.transpose(Image.Transpose.ROTATE_90)
        exif[0x0112] = 6  # stored turned left; upright is 400 x 300 again
    img.save(folder / f"{name}.jpg", exif=exif)
    (folder / f"{name}.json").write_text(json.dumps({"card_corners": corners}), encoding="utf-8")


def test_evaluate_photos_reads_upright_and_counts_misses(tmp_path: Path) -> None:
    from ml.eval import evaluate_photos

    corners = [[100, 50], [300, 50], [300, 170], [100, 170]]
    _labeled_photo(tmp_path, "a", corners, rotated=False)
    _labeled_photo(tmp_path, "b", corners, rotated=True)
    _labeled_photo(tmp_path, "c", corners, rotated=False)
    (tmp_path / "a.boxes.json").write_text("{}", encoding="utf-8")  # not a label
    sizes = []

    def model(image, conf, verbose):  # noqa: ANN001, ANN202
        sizes.append(image.size)
        if len(sizes) == 3:
            return [_Result([])]  # nothing detected on the third photo
        good = [(float(x), float(y)) for x, y in corners]
        worse = [(good[0][0] + 10, good[0][1])] + good[1:]  # 5% of the 200 px card width
        return [_Result([(0.4, worse), (0.9, good)])]  # the most confident one counts

    stats = evaluate_photos(model, tmp_path)
    assert sizes == [(400, 300)] * 3  # the rotated photo is read upright
    assert stats["photos"] == 3 and stats["missed"] == 1
    assert stats["max_pct"] == 0.0


def test_max_corner_pct_fails_the_cli(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import ml.eval as cli

    monkeypatch.setitem(sys.modules, "ultralytics", type("U", (), {"YOLO": lambda path: None}))
    stats = {"photos": 2, "missed": 0, "count": 2}
    stats |= {"median_pct": 2.0, "p95_pct": 2.5, "max_pct": 4.0}
    monkeypatch.setattr(cli, "evaluate_photos", lambda model, folder: stats)
    args = ["--model", "m.pt", "--photos", str(tmp_path)]
    cli.main(args + ["--max-corner-pct", "5"])
    with pytest.raises(SystemExit) as exc:  # p95 is within 3%, but one photo is not
        cli.main(args + ["--max-corner-pct", "3"])
    assert exc.value.code == 1
