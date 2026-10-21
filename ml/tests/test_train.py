"""Unit and smoke tests for YOLO11n-pose training and CLI entry point."""

from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from ml.train import prepare_smoke_dataset, train_yolo_pose

HAS_ULTRALYTICS = False
try:
    import ultralytics  # noqa: F401
    HAS_ULTRALYTICS = True
except ImportError:
    pass


def test_prepare_smoke_dataset(tmp_path: Path):
    smoke_dir = tmp_path / "smoke_data"
    yaml_path = prepare_smoke_dataset(smoke_dir)

    assert yaml_path.is_file()
    assert (smoke_dir / "yolo" / "images" / "train").is_dir()
    assert (smoke_dir / "yolo" / "labels" / "train").is_dir()

    # Total samples should be 50 (42 positive cards + 8 negatives)
    all_images = list((smoke_dir / "yolo" / "images").glob("*/*.png"))
    assert len(all_images) == 50


def test_train_yolo_pose_mocked(tmp_path: Path):
    dummy_yaml = tmp_path / "dataset.yaml"
    dummy_yaml.write_text("path: .\nkpt_shape: [4, 2]\n", encoding="utf-8")

    mock_model = MagicMock()
    mock_model.train.return_value = {"mAP50": 0.85}

    weights_dir = tmp_path / "runs" / "smoke" / "weights"
    weights_dir.mkdir(parents=True)
    (weights_dir / "best.pt").write_bytes(b"dummy_weights")

    with patch("ultralytics.YOLO", return_value=mock_model), \
         patch("ml.train.export_litert_models", return_value={"mock": True}):
        res = train_yolo_pose(
            dummy_yaml,
            epochs=1,
            project=str(tmp_path / "runs"),
            name="smoke",
            export_after_training=True,
        )

        assert res["weights_path"] == str(weights_dir / "best.pt")
        mock_model.train.assert_called_once()


@pytest.mark.skipif(not HAS_ULTRALYTICS, reason="ultralytics/torch not installed (train extra)")
def test_smoke_train_live_execution(tmp_path: Path):
    # Live smoke training: 1 epoch on 50 images in under 10 minutes on CPU
    smoke_dir = tmp_path / "live_smoke"
    yaml_path = prepare_smoke_dataset(smoke_dir)

    res = train_yolo_pose(
        yaml_path,
        epochs=1,
        imgsz=640,
        batch=4,
        device="cpu",
        project=str(tmp_path / "runs"),
        name="smoke_test",
        export_after_training=True,
    )

    assert res["elapsed_seconds"] < 600.0  # under 10 minutes
    assert Path(res["weights_path"]).is_file()
    assert "export" in res
    assert Path(res["export"]["metadata_path"]).is_file()
