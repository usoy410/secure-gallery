"""Dataset generation and orchestration engine for Secure Gallery benchmark.

Parses YAML configurations to produce complete, partitioned train/val/test datasets
incorporating:
1. Synthetic Philippine ID and payment card variants with conditions and layout jitter.
2. Negative non-ID images (receipts, loyalty cards, blank cards, rectangles, screenshots, photos).
3. Defective / failed redaction test cases built from test split card samples.
4. Identity-based split assignment ensuring zero data leakage across splits.
5. manifest.csv catalog and generated DATASHEET.md.
"""

from __future__ import annotations

import csv
import json
import logging
import zlib
from pathlib import Path
from typing import Any

import yaml

from ml.conditions import apply_condition, parse_conditions_arg
from ml.datasheet import build_datasheet_markdown
from ml.failed_redactions import FAILED_REDACTION_TYPES, apply_failed_redaction
from ml.generate import (
    generate_single_sample,
    get_available_templates,
)
from ml.negatives import (
    NEGATIVE_TYPES,
    generate_negative_sample,
    get_valid_curated_photos,
)
from ml.splits import (
    DEFAULT_SPLIT_RATIOS,
    compute_stratified_split_map,
    validate_split_isolation,
)

logger = logging.getLogger(__name__)

DEFAULT_CONFIG: dict[str, Any] = {
    "dataset": {
        "name": "Secure Gallery Benchmark Dataset",
        "out_dir": "data/benchmark",
        "seed": 42,
        "splits": DEFAULT_SPLIT_RATIOS,
    },
    "cards": {
        "variants": "all",
        "count_per_variant": 5,
        "composite": True,
        "jitter": True,
        "conditions": "all",
    },
    "negatives": {
        "enabled": True,
        "count_per_type": 5,
        "types": list(NEGATIVE_TYPES),
        "photos_dir": "data/photos",
    },
    "failed_redactions": {
        "enabled": True,
        "types": list(FAILED_REDACTION_TYPES),
    },
}


def load_dataset_config(config_source: str | Path | dict[str, Any]) -> dict[str, Any]:
    """Load and merge dataset configuration against defaults.

    Args:
        config_source: Path to YAML config file or dictionary.

    Returns:
        Merged configuration dictionary.
    """
    if isinstance(config_source, str | Path):
        p = Path(config_source)
        if not p.is_file():
            raise FileNotFoundError(f"Dataset config file not found: {p}")
        with open(p, encoding="utf-8") as f:
            user_config = yaml.safe_load(f) or {}
    elif isinstance(config_source, dict):
        user_config = config_source
    else:
        raise TypeError(f"Expected path or dict, got {type(config_source)}")

    cfg: dict[str, Any] = {
        "dataset": {**DEFAULT_CONFIG["dataset"], **user_config.get("dataset", {})},
        "cards": {**DEFAULT_CONFIG["cards"], **user_config.get("cards", {})},
        "negatives": {**DEFAULT_CONFIG["negatives"], **user_config.get("negatives", {})},
        "failed_redactions": {
            **DEFAULT_CONFIG["failed_redactions"],
            **user_config.get("failed_redactions", {}),
        },
    }
    return cfg


def build_full_dataset(
    config_source: str | Path | dict[str, Any],
    out_dir_override: str | Path | None = None,
) -> dict[str, Any]:
    """Execute complete end-to-end dataset generation from configuration.

    Args:
        config_source: Configuration path or dictionary.
        out_dir_override: Optional path to override config destination directory.

    Returns:
        Summary dictionary with generated record counts, splits, and paths.
    """
    cfg = load_dataset_config(config_source)

    out_dir = Path(out_dir_override or cfg["dataset"]["out_dir"])
    images_dir = out_dir / "images"
    labels_dir = out_dir / "labels"
    images_dir.mkdir(parents=True, exist_ok=True)
    labels_dir.mkdir(parents=True, exist_ok=True)

    seed: int = int(cfg["dataset"]["seed"])
    split_ratios: dict[str, float] = cfg["dataset"].get("splits", DEFAULT_SPLIT_RATIOS)

    manifest_records: list[dict[str, Any]] = []

    # 1. Generate Positive Card Samples
    cards_cfg = cfg.get("cards", {})
    available_templates = get_available_templates()
    req_variants = cards_cfg.get("variants", "all")
    if req_variants == "all":
        target_variants = sorted(available_templates.keys())
    elif isinstance(req_variants, list):
        target_variants = req_variants
    else:
        target_variants = [req_variants]

    count_per_variant = int(cards_cfg.get("count_per_variant", 5))
    do_composite = bool(cards_cfg.get("composite", True))
    do_jitter = bool(cards_cfg.get("jitter", True))
    cond_arg = cards_cfg.get("conditions", None)
    parsed_conditions = parse_conditions_arg(cond_arg) if cond_arg else []

    failed_redact_cfg = cfg.get("failed_redactions", {})
    do_failed_redactions = bool(failed_redact_cfg.get("enabled", True))
    redaction_types = failed_redact_cfg.get("types", list(FAILED_REDACTION_TYPES))

    for v_id in target_variants:
        tmpl = available_templates[v_id]
        v_offset = zlib.crc32(v_id.encode("utf-8")) % 10000
        split_map = compute_stratified_split_map(
            count_per_variant, split_ratios, seed=seed + v_offset
        )

        for idx in range(count_per_variant):
            sample_seed = seed + idx * 1000 + v_offset
            base_name = f"{v_id}_{idx:04d}"
            assigned_split = split_map.get(idx, "train")

            card_img, card_label = generate_single_sample(
                tmpl,
                seed=sample_seed,
                composite=do_composite,
                jitter=do_jitter,
            )

            card_label["base_image_id"] = base_name
            card_label["split"] = assigned_split
            card_label["condition"] = None
            card_label["severity"] = None
            card_label["is_negative"] = False
            card_label["negative_type"] = "none"
            card_label["is_failed_redaction"] = False
            card_label["redaction_type"] = "none"
            card_label["expected_leak"] = "none"

            img_rel = f"images/{base_name}.png"
            json_rel = f"labels/{base_name}.json"
            card_label["image_path"] = img_rel

            card_img.save(out_dir / img_rel, format="PNG", optimize=False)
            with open(out_dir / json_rel, "w", encoding="utf-8") as jf:
                json.dump(card_label, jf, indent=2, sort_keys=True)

            rec = {
                "split": assigned_split,
                "image_path": img_rel,
                "label_path": json_rel,
                "base_image_id": base_name,
                "class": card_label["class"],
                "document_class": card_label["document_class"],
                "variant": v_id,
                "condition": "none",
                "severity": 0,
                "is_negative": False,
                "negative_type": "none",
                "is_failed_redaction": False,
                "redaction_type": "none",
                "expected_leak": "none",
                "width": card_img.width,
                "height": card_img.height,
                "photo_source": "",
            }
            manifest_records.append(rec)

            # Apply Conditions (staying strictly in assigned_split)
            for cond_name, sev in parsed_conditions:
                cond_seed = sample_seed + zlib.crc32(f"{cond_name}_{sev}".encode()) % 50000
                c_img, c_label = apply_condition(
                    card_img,
                    card_label,
                    condition=cond_name,
                    severity=sev,
                    seed=cond_seed,
                )
                c_label["split"] = assigned_split
                c_label["is_negative"] = False
                c_label["negative_type"] = "none"
                c_label["is_failed_redaction"] = False
                c_label["redaction_type"] = "none"
                c_label["expected_leak"] = "none"

                c_base = f"{base_name}_{cond_name}_s{sev}"
                c_img_rel = f"images/{c_base}.png"
                c_json_rel = f"labels/{c_base}.json"
                c_label["image_path"] = c_img_rel

                c_img.save(out_dir / c_img_rel, format="PNG", optimize=False)
                with open(out_dir / c_json_rel, "w", encoding="utf-8") as jf:
                    json.dump(c_label, jf, indent=2, sort_keys=True)

                manifest_records.append({
                    "split": assigned_split,
                    "image_path": c_img_rel,
                    "label_path": c_json_rel,
                    "base_image_id": base_name,
                    "class": c_label["class"],
                    "document_class": c_label["document_class"],
                    "variant": v_id,
                    "condition": cond_name,
                    "severity": sev,
                    "is_negative": False,
                    "negative_type": "none",
                    "is_failed_redaction": False,
                    "redaction_type": "none",
                    "expected_leak": "none",
                    "width": c_img.width,
                    "height": c_img.height,
                    "photo_source": "",
                })

            # Generate Failed Redactions (only on TEST split samples)
            if do_failed_redactions and assigned_split == "test":
                for r_type in redaction_types:
                    r_seed = sample_seed + zlib.crc32(f"redact_{r_type}".encode()) % 50000
                    r_img, r_label = apply_failed_redaction(
                        card_img,
                        card_label,
                        redaction_type=r_type,
                        seed=r_seed,
                    )
                    r_label["split"] = "test"
                    r_label["base_image_id"] = base_name
                    r_label["is_negative"] = False
                    r_label["negative_type"] = "none"

                    r_base = f"{base_name}_redact_{r_type}"
                    r_img_rel = f"images/{r_base}.png"
                    r_json_rel = f"labels/{r_base}.json"
                    r_label["image_path"] = r_img_rel

                    r_img.save(out_dir / r_img_rel, format="PNG", optimize=False)
                    with open(out_dir / r_json_rel, "w", encoding="utf-8") as jf:
                        json.dump(r_label, jf, indent=2, sort_keys=True)

                    manifest_records.append({
                        "split": "test",
                        "image_path": r_img_rel,
                        "label_path": r_json_rel,
                        "base_image_id": base_name,
                        "class": r_label["class"],
                        "document_class": r_label["document_class"],
                        "variant": v_id,
                        "condition": "none",
                        "severity": 0,
                        "is_negative": False,
                        "negative_type": "none",
                        "is_failed_redaction": r_label.get("is_failed_redaction", False),
                        "redaction_type": r_type,
                        "expected_leak": r_label.get("expected_leak", "none"),
                        "width": r_img.width,
                        "height": r_img.height,
                        "photo_source": "",
                    })

    # 2. Generate Negative Samples
    neg_cfg = cfg.get("negatives", {})
    if neg_cfg.get("enabled", True):
        neg_types = neg_cfg.get("types", list(NEGATIVE_TYPES))
        count_per_neg = int(neg_cfg.get("count_per_type", 5))
        photos_dir = neg_cfg.get("photos_dir", "data/photos")

        for n_type in neg_types:
            n_offset = zlib.crc32(n_type.encode("utf-8")) % 10000

            if n_type == "photo":
                valid_photos = get_valid_curated_photos(photos_dir)
                if valid_photos:
                    # Key the split for photo negatives strictly on photo identity (source file)
                    # so that one photo source never spans across splits.
                    photo_split_map = compute_stratified_split_map(
                        len(valid_photos), split_ratios, seed=seed + n_offset
                    )
                    photo_to_split: dict[str, str] = {}
                    for p_i, (p_path, p_meta) in enumerate(valid_photos):
                        s_name = photo_split_map[p_i]
                        photo_to_split[p_path.name] = s_name
                        photo_to_split[p_meta["source"]] = s_name
                        if p_meta.get("id"):
                            photo_to_split[p_meta["id"]] = s_name
                else:
                    photo_to_split = {}
                    n_split_map = compute_stratified_split_map(
                        count_per_neg, split_ratios, seed=seed + n_offset
                    )
            else:
                valid_photos = []
                photo_to_split = {}
                n_split_map = compute_stratified_split_map(
                    count_per_neg, split_ratios, seed=seed + n_offset
                )

            for idx in range(count_per_neg):
                n_seed = seed + idx * 1000 + n_offset
                base_name = f"neg_{n_type}_{idx:04d}"

                if n_type == "photo":
                    # Pass photo_index=idx so all curated photos are cycled through and used
                    n_img, n_label = generate_negative_sample(
                        negative_type=n_type,
                        seed=n_seed,
                        photos_dir=photos_dir,
                        photo_index=idx,
                    )
                    if valid_photos:
                        photo_key = n_label.get("photo_filename") or n_label.get("photo_source")
                        assigned_split = photo_to_split.get(
                            photo_key,
                            photo_to_split.get(n_label.get("photo_source", ""), "train"),
                        )
                    else:
                        assigned_split = n_split_map.get(idx, "train")
                else:
                    assigned_split = n_split_map.get(idx, "train")
                    n_img, n_label = generate_negative_sample(
                        negative_type=n_type,
                        seed=n_seed,
                        photos_dir=photos_dir,
                    )

                n_label["base_image_id"] = base_name
                n_label["split"] = assigned_split
                n_label["is_failed_redaction"] = False
                n_label["redaction_type"] = "none"
                n_label["expected_leak"] = "none"

                n_img_rel = f"images/{base_name}.png"
                n_json_rel = f"labels/{base_name}.json"
                n_label["image_path"] = n_img_rel

                n_img.save(out_dir / n_img_rel, format="PNG", optimize=False)
                with open(out_dir / n_json_rel, "w", encoding="utf-8") as jf:
                    json.dump(n_label, jf, indent=2, sort_keys=True)

                manifest_records.append({
                    "split": assigned_split,
                    "image_path": n_img_rel,
                    "label_path": n_json_rel,
                    "base_image_id": base_name,
                    "class": "negative",
                    "document_class": "negative",
                    "variant": f"negative-{n_type}",
                    "condition": "none",
                    "severity": 0,
                    "is_negative": True,
                    "negative_type": n_type,
                    "is_failed_redaction": False,
                    "redaction_type": "none",
                    "expected_leak": "none",
                    "width": n_img.width,
                    "height": n_img.height,
                    "photo_source": n_label.get("photo_source", ""),
                })

    # 3. Write manifest.csv
    manifest_path = out_dir / "manifest.csv"
    csv_fields = [
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
        "photo_source",
    ]
    with open(manifest_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=csv_fields)
        writer.writeheader()
        writer.writerows(manifest_records)

    # 4. Verify zero data leakage
    validation = validate_split_isolation(manifest_records)
    if not validation["is_clean"]:
        n_leak = validation["leaking_bases_count"]
        leaking = list(validation["leaking_bases"].keys())[:5]
        raise RuntimeError(
            f"Split leakage detected: {n_leak} base identities cross splits! Bases: {leaking}"
        )

    # 5. Generate DATASHEET.md
    datasheet_md = build_datasheet_markdown(
        manifest_records,
        dataset_name=cfg["dataset"].get("name", "Secure Gallery Benchmark Dataset"),
        seed=seed,
    )
    datasheet_path = out_dir / "DATASHEET.md"
    with open(datasheet_path, "w", encoding="utf-8") as f:
        f.write(datasheet_md)

    logger.info(
        "Dataset generation complete: %d samples in %s (leak-free: %s)",
        len(manifest_records),
        out_dir,
        validation["is_clean"],
    )

    return {
        "out_dir": str(out_dir),
        "manifest_path": str(manifest_path),
        "datasheet_path": str(datasheet_path),
        "total_samples": len(manifest_records),
        "validation": validation,
    }
