"""Identity-based dataset splitting logic avoiding data leakage.

Ensures that:
1. Split assignment is based on base identity and template seed.
2. All condition variants (blur, glare, rotation, etc.) and failed-redaction
   derivatives of a base image land strictly in the same split.
3. No base identity crosses splits (train, val, test are mutually exclusive).
"""

from __future__ import annotations

import random
from typing import Any

DEFAULT_SPLIT_RATIOS: dict[str, float] = {
    "train": 0.70,
    "val": 0.15,
    "test": 0.15,
}


def normalize_split_ratios(ratios: dict[str, float]) -> dict[str, float]:
    """Normalize split ratios so they sum to 1.0.

    Args:
        ratios: Dictionary mapping split names ('train', 'val', 'test') to weights.

    Returns:
        Normalized dictionary with weights summing to 1.0.
    """
    total = sum(ratios.values())
    if total <= 0:
        raise ValueError(f"Sum of split ratios must be positive, got {total}")
    return {k: v / total for k, v in ratios.items()}


def compute_stratified_split_map(
    count: int,
    split_ratios: dict[str, float] | None = None,
    seed: int = 42,
) -> dict[int, str]:
    """Compute deterministic index-to-split assignments with guaranteed exact proportions.

    Useful when generating N samples per variant to guarantee every split receives
    its designated quota of base identities, even with small sample sizes.

    Args:
        count: Total number of base samples.
        split_ratios: Dictionary of split proportions.
        seed: Random seed for deterministic index shuffle.

    Returns:
        Dictionary mapping integer sample index [0..count-1] to split name.
    """
    if count <= 0:
        return {}

    ratios = normalize_split_ratios(split_ratios or DEFAULT_SPLIT_RATIOS)
    train_r = ratios.get("train", 0.70)
    val_r = ratios.get("val", 0.15)

    n_train = int(round(count * train_r))
    n_val = int(round(count * val_r))
    n_test = count - n_train - n_val

    # Ensure at least 1 sample in test/val if count >= 3
    if count >= 3:
        if n_val == 0:
            n_val = 1
            if n_train > 1:
                n_train -= 1
            else:
                n_test -= 1
        if n_test == 0:
            n_test = 1
            if n_train > 1:
                n_train -= 1
            else:
                n_val -= 1

    assignments: list[str] = (
        ["train"] * n_train + ["val"] * n_val + ["test"] * n_test
    )

    rng = random.Random(seed)
    indices = list(range(count))
    rng.shuffle(indices)

    return {idx: split for idx, split in zip(indices, assignments, strict=False)}


def validate_split_isolation(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Assert and verify that no base_image_id or photo source crosses split boundaries.

    Args:
        records: List of manifest records containing 'base_image_id', 'split',
                 and optionally 'photo_source'.

    Returns:
        Dictionary with validation results, counts, and leakage status.
    """
    splits_by_base: dict[str, set[str]] = {}
    splits_by_photo: dict[str, set[str]] = {}

    for r in records:
        base_id = r["base_image_id"]
        split = r["split"]
        splits_by_base.setdefault(base_id, set()).add(split)

        photo_source = r.get("photo_source")
        if photo_source and photo_source != "procedurally_generated_scene":
            splits_by_photo.setdefault(photo_source, set()).add(split)

    leaking_bases = {
        base_id: splits for base_id, splits in splits_by_base.items() if len(splits) > 1
    }
    leaking_photos = {
        psrc: splits for psrc, splits in splits_by_photo.items() if len(splits) > 1
    }

    train_bases = {r["base_image_id"] for r in records if r["split"] == "train"}
    val_bases = {r["base_image_id"] for r in records if r["split"] == "val"}
    test_bases = {r["base_image_id"] for r in records if r["split"] == "test"}

    leakage_train_val = train_bases.intersection(val_bases)
    leakage_train_test = train_bases.intersection(test_bases)
    leakage_val_test = val_bases.intersection(test_bases)

    combined_leaks: dict[str, set[str]] = {**leaking_bases}
    for psrc, splits in leaking_photos.items():
        combined_leaks[f"photo_source:{psrc}"] = splits

    is_clean = (
        len(combined_leaks) == 0
        and len(leakage_train_val) == 0
        and len(leakage_train_test) == 0
        and len(leakage_val_test) == 0
    )

    return {
        "is_clean": is_clean,
        "total_records": len(records),
        "unique_bases": len(splits_by_base),
        "train_bases": len(train_bases),
        "val_bases": len(val_bases),
        "test_bases": len(test_bases),
        "leaking_bases_count": len(combined_leaks),
        "leaking_bases": combined_leaks,
        "leaking_photos": leaking_photos,
    }
