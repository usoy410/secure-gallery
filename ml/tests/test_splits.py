"""Pytest test suite for identity-based dataset splits.

Validates Issue #7 acceptance criteria:
1. Splits by base identity and template seed.
2. All condition variants of a base image land in the same split.
3. Assert no base ID crosses splits (train, val, test are mutually exclusive).
"""

from __future__ import annotations

import contextlib
import csv
import hashlib
import tempfile
from pathlib import Path

from PIL import Image

from ml.dataset import build_full_dataset
from ml.fetch_photos import PUBLIC_DOMAIN_PHOTOS
from ml.splits import (
    compute_stratified_split_map,
    validate_split_isolation,
)


@contextlib.contextmanager
def mock_curated_photos_env(dest_dir: Path):
    """Context manager to populate dest_dir with verified mock JPEGs matching curated list."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    orig_hashes = [(p["id"], p["sha256"], p["sha1"]) for p in PUBLIC_DOMAIN_PHOTOS]
    for i, entry in enumerate(PUBLIC_DOMAIN_PHOTOS):
        img_path = dest_dir / entry["filename"]
        img = Image.new("RGB", (200, 200), (30 * (i + 1), 40 * (i + 1), 50 * (i + 1)))
        img.save(img_path, format="JPEG")
        with open(img_path, "rb") as f:
            b = f.read()
        entry["sha256"] = hashlib.sha256(b).hexdigest()
        entry["sha1"] = hashlib.sha1(b).hexdigest()
    try:
        yield
    finally:
        hash_map = {item[0]: (item[1], item[2]) for item in orig_hashes}
        for entry in PUBLIC_DOMAIN_PHOTOS:
            if entry["id"] in hash_map:
                entry["sha256"], entry["sha1"] = hash_map[entry["id"]]


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
    """Verify validate_split_isolation correctly detects when a base ID or photo crosses splits."""
    clean_records = [
        {"base_image_id": "card_01", "split": "train"},
        {"base_image_id": "card_01", "split": "train"},
        {"base_image_id": "card_02", "split": "val"},
        {"base_image_id": "card_03", "split": "test"},
        {"base_image_id": "neg_photo_01", "split": "train", "photo_source": "photo_a"},
        {"base_image_id": "neg_photo_02", "split": "train", "photo_source": "photo_a"},
        {"base_image_id": "neg_photo_03", "split": "test", "photo_source": "photo_b"},
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

    # Photo source spanning across splits must be detected as leakage
    photo_leaking_records = [
        {"base_image_id": "neg_photo_0000", "split": "test", "photo_source": "Desk_and_Bookcase"},
        {"base_image_id": "neg_photo_0001", "split": "train", "photo_source": "Desk_and_Bookcase"},
    ]
    res_photo_leaking = validate_split_isolation(photo_leaking_records)
    assert res_photo_leaking["is_clean"] is False
    assert "photo_source:Desk_and_Bookcase" in res_photo_leaking["leaking_bases"]


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


def test_photo_negatives_distinct_sources_and_no_split_crossing() -> None:
    """Verify >1 distinct photo sources are used and no photo source crosses splits.

    Validates review finding:
    1. Photo negatives select photos so all curated photos are used (distinct sources > 1).
    2. Split assignment for photo negatives is keyed on photo identity (source file).
    3. Even when photos repeat across samples, no photo source spans across splits.
    """
    with tempfile.TemporaryDirectory() as td:
        base_dir = Path(td)
        out_dir = base_dir / "dataset"
        photos_dir = base_dir / "photos"

        with mock_curated_photos_env(photos_dir):
            cfg = {
                "dataset": {
                    "out_dir": str(out_dir),
                    "seed": 42,
                    "splits": {"train": 0.70, "val": 0.15, "test": 0.15},
                },
                "cards": {
                    "variants": ["philsys-card"],
                    "count_per_variant": 2,
                    "composite": False,
                    "jitter": False,
                },
                "negatives": {
                    "enabled": True,
                    "types": ["photo"],
                    "count_per_type": 10,
                    "photos_dir": str(photos_dir),
                },
                "failed_redactions": {"enabled": False},
            }

            res = build_full_dataset(cfg)
            manifest_path = Path(res["manifest_path"])

            with open(manifest_path, encoding="utf-8") as f:
                records = list(csv.DictReader(f))

            photo_records = [r for r in records if r["variant"] == "negative-photo"]
            assert len(photo_records) == 10

            # 1. Assert >1 distinct photo source used in multi-sample run (in fact, all 5)
            distinct_sources = {r["photo_source"] for r in photo_records}
            assert len(distinct_sources) > 1, f"Expected >1 photo source, got: {distinct_sources}"
            assert len(distinct_sources) == len(PUBLIC_DOMAIN_PHOTOS)

            # 2. Assert no photo source crosses splits (each source is in exactly one split)
            for src in distinct_sources:
                splits_for_source = {r["split"] for r in photo_records if r["photo_source"] == src}
                assert len(splits_for_source) == 1, (
                    f"Photo source '{src}' crossed splits: {splits_for_source}"
                )

            # 3. Assert multiple splits receive photo negatives
            splits_present = {r["split"] for r in photo_records}
            assert len(splits_present) > 1, f"Expected multiple splits, got: {splits_present}"

            # 4. Overall dataset split isolation validation must pass cleanly
            assert res["validation"]["is_clean"] is True
            assert res["validation"]["leaking_bases_count"] == 0
