"""Pytest test suite for identity-based dataset splits.

Validates Issue #7 acceptance criteria:
1. Splits by base identity and template seed.
2. All condition variants of a base image land in the same split.
3. Assert no base ID crosses splits (train, val, test are mutually exclusive).
"""

from __future__ import annotations

import tempfile
from pathlib import Path

from ml.dataset import build_full_dataset
from ml.splits import (
    compute_stratified_split_map,
    validate_split_isolation,
)


def test_stratified_split_map_exact_counts() -> None:
    """Verify stratified index map produces expected sample counts for splits."""
    split_map = compute_stratified_split_map(
        count=20,
        split_ratios={"train": 0.70, "val": 0.15, "test": 0.15},
        seed=100,
    )
    assert len(split_map) == 20

    counts = {"train": 0, "val": 0, "test": 0}
    for s in split_map.values():
        counts[s] += 1

    assert counts["train"] == 14
    assert counts["val"] == 3
    assert counts["test"] == 3


def test_validate_split_isolation_detects_leakage() -> None:
    """Verify validate_split_isolation correctly detects when a base ID crosses splits."""
    clean_records = [
        {"base_image_id": "card_01", "split": "train"},
        {"base_image_id": "card_01", "split": "train"},
        {"base_image_id": "card_02", "split": "val"},
        {"base_image_id": "card_03", "split": "test"},
    ]
    res_clean = validate_split_isolation(clean_records)
    assert res_clean["is_clean"] is True

    leaking_records = [
        {"base_image_id": "card_01", "split": "train"},
        {"base_image_id": "card_01", "split": "val"},  # Leak!
        {"base_image_id": "card_02", "split": "test"},
    ]
    res_leaking = validate_split_isolation(leaking_records)
    assert res_leaking["is_clean"] is False
    assert "card_01" in res_leaking["leaking_bases"]


def test_no_base_id_crosses_splits_in_generated_dataset() -> None:
    """Assert that in a generated dataset, no base ID crosses splits."""
    with tempfile.TemporaryDirectory() as td:
        cfg = {
            "dataset": {"out_dir": td, "seed": 42},
            "cards": {
                "variants": ["philsys-card", "lto-dl-current"],
                "count_per_variant": 5,
                "conditions": "blur:1,glare:1",
            },
            "negatives": {
                "enabled": True,
                "count_per_type": 3,
                "types": ["receipt", "loyalty_card", "blank_card"],
            },
            "failed_redactions": {
                "enabled": True,
                "types": ["complete", "partial", "misplaced", "ineffective"],
            },
        }

        res = build_full_dataset(cfg)
        validation = res["validation"]

        assert validation["is_clean"] is True
        assert validation["leaking_bases_count"] == 0

        # Read back manifest and independently assert disjoint base sets
        import csv
        manifest_path = Path(res["manifest_path"])
        with open(manifest_path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            train_bases = {r["base_image_id"] for r in reader if r["split"] == "train"}
        with open(manifest_path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            val_bases = {r["base_image_id"] for r in reader if r["split"] == "val"}
        with open(manifest_path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            test_bases = {r["base_image_id"] for r in reader if r["split"] == "test"}

        # Strict disjoint assertions
        assert len(train_bases.intersection(val_bases)) == 0, "Base ID crossed train and val!"
        assert len(train_bases.intersection(test_bases)) == 0, "Base ID crossed train and test!"
        assert len(val_bases.intersection(test_bases)) == 0, "Base ID crossed val and test!"
