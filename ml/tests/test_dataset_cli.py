"""Pytest test suite for dataset generation CLI and end-to-end manifest generation.

Validates Issue #7 acceptance criteria:
1. 'python -m ml.generate dataset --config <yaml>' builds train/val/test dataset with manifest.
2. manifest.csv contains expected columns and populated records.
3. DATASHEET.md is generated with class counts, conditions, splits, and gaps.
4. Backward compatibility: 'python -m ml.generate --variant ...' continues to work.
"""

from __future__ import annotations

import csv
import subprocess
import sys
from pathlib import Path

import yaml


def test_cli_dataset_generation_end_to_end(tmp_path: Path) -> None:
    """Verify 'python -m ml.generate dataset --config <yaml>' builds dataset with manifest."""
    config_file = tmp_path / "test_config.yaml"
    out_dir = tmp_path / "dataset_out"

    config_data = {
        "dataset": {
            "name": "Test Capstone Benchmark",
            "out_dir": str(out_dir),
            "seed": 888,
            "splits": {"train": 0.60, "val": 0.20, "test": 0.20},
        },
        "cards": {
            "variants": ["philsys-card", "payment-card-front"],
            "count_per_variant": 3,
            "composite": True,
            "jitter": False,
            "conditions": "blur:1",
        },
        "negatives": {
            "enabled": True,
            "count_per_type": 2,
            "types": ["receipt", "card_rectangle", "screenshot"],
        },
        "failed_redactions": {
            "enabled": True,
            "types": ["complete", "partial", "misplaced", "ineffective"],
        },
    }

    with open(config_file, "w", encoding="utf-8") as f:
        yaml.safe_dump(config_data, f)

    cmd = [
        sys.executable,
        "-m",
        "ml.generate",
        "dataset",
        "--config",
        str(config_file),
    ]

    res = subprocess.run(cmd, check=True, capture_output=True, text=True)
    assert res.returncode == 0
    assert "Generated benchmark dataset" in res.stdout

    # Verify manifest.csv
    manifest_csv = out_dir / "manifest.csv"
    assert manifest_csv.is_file(), "manifest.csv was not generated"

    with open(manifest_csv, encoding="utf-8") as f:
        reader = list(csv.DictReader(f))

    assert len(reader) > 0
    required_cols = {
        "split",
        "image_path",
        "label_path",
        "base_image_id",
        "class",
        "document_class",
        "variant",
        "condition",
        "severity",
        "is_negative",
        "negative_type",
        "is_failed_redaction",
        "redaction_type",
        "expected_leak",
        "width",
        "height",
    }
    assert required_cols.issubset(set(reader[0].keys()))

    # Verify every image and label file exists
    for row in reader:
        img_f = out_dir / row["image_path"]
        json_f = out_dir / row["label_path"]
        assert img_f.is_file(), f"Missing image file: {img_f}"
        assert json_f.is_file(), f"Missing label file: {json_f}"

    # Verify DATASHEET.md
    datasheet_file = out_dir / "DATASHEET.md"
    assert datasheet_file.is_file(), "DATASHEET.md was not generated in output directory"
    ds_text = datasheet_file.read_text(encoding="utf-8")
    assert "Test Capstone Benchmark" in ds_text
    assert "Counts by Split" in ds_text
    assert "Negative Sample Composition" in ds_text
    assert "Failed Redaction Cases" in ds_text
    assert "Known Gaps and Limitations" in ds_text


def test_cli_backward_compatibility(tmp_path: Path) -> None:
    """Verify legacy 'python -m ml.generate --variant ...' usage remains fully functional."""
    out_dir = tmp_path / "legacy_out"
    cmd = [
        sys.executable,
        "-m",
        "ml.generate",
        "--variant",
        "philsys-card",
        "--count",
        "1",
        "--seed",
        "42",
        "--out",
        str(out_dir),
    ]
    res = subprocess.run(cmd, check=True, capture_output=True, text=True)
    assert res.returncode == 0
    assert "Generated 1 synthetic card samples" in res.stdout

    img_file = out_dir / "philsys-card_0000.png"
    json_file = out_dir / "philsys-card_0000.json"
    assert img_file.is_file()
    assert json_file.is_file()
