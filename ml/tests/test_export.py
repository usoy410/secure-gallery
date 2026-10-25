"""Unit tests for LiteRT model export pipeline and budget assertions."""

import sys
import types
from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pytest

from ml.export import (
    export_litert_models,
    export_single_precision,
    get_file_size_mb,
    verify_int8_tensors,
)
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


def test_export_litert_models_mocked(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("ml.export.verify_int8_tensors", lambda path: 1)
    # Stand-in for ultralytics so the mocked path runs without the heavy train extra.
    monkeypatch.setitem(sys.modules, "ultralytics", types.ModuleType("ultralytics"))
    sys.modules["ultralytics"].YOLO = MagicMock()
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


def test_failed_int8_export_does_not_retry_as_float():
    model = MagicMock()
    model.export.side_effect = RuntimeError("calibration failed")
    with pytest.raises(RuntimeError, match="calibration failed"):
        export_single_precision(model, "int8", dataset_yaml="dataset.yaml")
    model.export.assert_called_once_with(
        imgsz=640, format="litert", quantize=8, data="dataset.yaml"
    )


def test_w8a16_receives_calibration_data(monkeypatch):
    monkeypatch.setattr("ml.export.verify_int8_tensors", lambda path: 1)
    model = MagicMock()
    model.export.return_value = "model.tflite"
    export_single_precision(model, "w8a16", dataset_yaml="calibration.yaml")
    model.export.assert_called_once_with(
        imgsz=640, format="litert", quantize="w8a16", data="calibration.yaml"
    )


@pytest.mark.parametrize(
    ("dtype", "scales", "valid"),
    [
        (np.float32, [], False),
        (np.int32, [1.0], False),
        (np.int8, [], False),
        (np.int8, [0.01], True),
    ],
)
def test_verify_int8_checks_quantized_tensors_not_float_io(monkeypatch, dtype, scales, valid):
    interpreter = MagicMock()
    interpreter.get_tensor_details.return_value = [
        {"dtype": np.float32, "quantization_parameters": {"scales": []}},
        {"dtype": dtype, "quantization_parameters": {"scales": scales}},
    ]
    module = types.ModuleType("ai_edge_litert.interpreter")
    module.Interpreter = MagicMock(return_value=interpreter)
    monkeypatch.setitem(sys.modules, "ai_edge_litert.interpreter", module)
    if valid:
        assert verify_int8_tensors("model.tflite") == 1
    else:
        with pytest.raises(ValueError, match="no scaled INT8 tensors"):
            verify_int8_tensors("model.tflite")
