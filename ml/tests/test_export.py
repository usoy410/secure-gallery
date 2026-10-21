"""Unit tests for LiteRT model export pipeline and budget assertions."""

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from ml.export import export_litert_models, get_file_size_mb
from ml.metadata import load_model_metadata


def test_get_file_size_mb(tmp_path: Path):
    dummy_file = tmp_path / "model.tflite"
    # Write 1 MB of bytes
    dummy_file.write_bytes(b"\x00" * (1024 * 1024))

    size_mb = get_file_size_mb(dummy_file)
    assert pytest.approx(size_mb, rel=1e-2) == 1.0


def test_get_file_size_mb_missing_file():
    with pytest.raises(FileNotFoundError):
        get_file_size_mb("non_existent_file.tflite")


def test_export_litert_models_mocked(tmp_path: Path):
    # Create fake exported file produced by model.export
    fake_exported = tmp_path / "fake_exported.tflite"
    fake_exported.write_bytes(b"\x00" * (3 * 1024 * 1024))  # 3 MB

    mock_model = MagicMock()
    mock_model.export.return_value = str(fake_exported)
    mock_model.names = {0: "philsys", 1: "drivers_license", 2: "payment_card"}

    out_dir = tmp_path / "exported"
    results = export_litert_models(
        mock_model,
        output_dir=out_dir,
        precisions=("int8", "fp16"),
        target_max_mb=10.0,
    )

    assert results["all_within_budget"] is True
    assert "int8" in results["models"]
    assert "fp16" in results["models"]
    assert results["models"]["int8"]["size_mb"] == pytest.approx(3.0, rel=1e-2)
    assert Path(results["metadata_path"]).is_file()

    # Verify metadata JSON contents
    meta = load_model_metadata(results["metadata_path"])
    assert meta["num_classes"] == 3
    assert meta["class_names"] == ["philsys", "drivers_license", "payment_card"]
    assert meta["target_budget_mb"] == 10.0
