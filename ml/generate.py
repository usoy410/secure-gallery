"""Synthetic ID and payment card generator CLI for Secure Gallery benchmark.

Runnable via:
    python -m ml.generate --variant <id> --count N --seed S --out <dir>

Produces synthetic ID and card images with pixel-accurate ground-truth JSON labels,
fictional demographic data (Faker fil_PH), Luhn-valid test card numbers, and
diagonal 'SPECIMEN – NOT VALID' watermarks.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import zlib
from pathlib import Path
from typing import Any

from ml.composite import composite_card
from ml.conditions import apply_condition, parse_conditions_arg
from ml.data_generator import SyntheticDataGenerator
from ml.renderer import render_card
from templates.validate import load_json

logger = logging.getLogger(__name__)

TEMPLATES_DIR = Path(__file__).resolve().parent.parent / "templates"


def get_available_templates() -> dict[str, dict[str, Any]]:
    """Scan and load all valid template JSON definitions from the templates directory.

    Returns:
        Dictionary mapping template ID string to parsed template JSON dictionary.
    """
    templates: dict[str, dict[str, Any]] = {}
    for path in sorted(TEMPLATES_DIR.glob("*.json")):
        if path.name == "schema.json":
            continue
        try:
            data = load_json(path)
            if "id" in data:
                templates[data["id"]] = data
        except (json.JSONDecodeError, OSError) as exc:
            logger.warning("Skipping invalid template file %s: %s", path, exc)
    return templates


def generate_single_sample(
    template: dict[str, Any],
    seed: int,
    composite: bool = True,
    bg_type: str | None = None,
    jitter: bool = False,
) -> tuple[Any, dict[str, Any]]:
    """Generate one synthetic card image and its corresponding ground-truth label dictionary.

    Args:
        template: Loaded template dictionary conforming to templates/schema.json.
        seed: Deterministic random seed for all random operations.
        composite: Whether to composite the card onto backgrounds with scale/rotation.
        bg_type: Optional background type override ('table', 'hand-held', 'screenshot_frame').
        jitter: Whether to apply layout jitter at render time.

    Returns:
        Tuple of (PIL_image, ground_truth_dictionary).
    """
    data_gen = SyntheticDataGenerator(seed=seed)
    field_values = data_gen.generate_fields_for_template(template)

    clean_img, clean_fields = render_card(template, field_values, seed=seed, jitter=jitter)

    variant_id = template["id"]
    doc_class = template["document_class"]

    if composite:
        final_img, corners, fields = composite_card(
            clean_img,
            clean_fields,
            seed=seed,
            bg_type=bg_type,
        )
    else:
        final_img = clean_img
        w, h = clean_img.size
        corners = [[0, 0], [w, 0], [w, h], [0, h]]
        fields = clean_fields

    label_data: dict[str, Any] = {
        "class": doc_class,
        "document_class": doc_class,
        "variant": variant_id,
        "card_corners": corners,
        "image_size": [final_img.width, final_img.height],
        "fields": fields,
        "layout_jitter": jitter,
    }

    return final_img, label_data


def generate_dataset(
    variant: str,
    count: int,
    seed: int,
    out_dir: str | Path,
    composite: bool = True,
    jitter: bool = False,
    conditions: str | list[tuple[str, int]] | None = None,
) -> list[tuple[Path, Path]]:
    """Generate synthetic card images and JSON labels for the specified variant.

    Args:
        variant: Template ID (e.g., 'philsys-card') or 'all' for every variant.
        count: Number of images to generate per variant.
        seed: Base random seed.
        out_dir: Destination directory.
        composite: Whether to composite onto varied backgrounds with rotation/scale.
        jitter: Whether to apply layout jitter (field offset, font size, print offset).
        conditions: Condition specification (e.g. 'all', 'blur,rotation', 'blur:1').

    Returns:
        List of generated (image_path, json_path) tuples.
    """
    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    available = get_available_templates()
    if not available:
        raise RuntimeError(f"No card templates found under {TEMPLATES_DIR}")

    if variant == "all":
        target_variants = list(available.keys())
    else:
        if variant not in available:
            raise ValueError(
                f"Unknown variant '{variant}'. Available variants: {sorted(available.keys())}"
            )
        target_variants = [variant]

    # Parse conditions specification
    if isinstance(conditions, str):
        parsed_conditions = parse_conditions_arg(conditions)
    elif isinstance(conditions, list):
        parsed_conditions = conditions
    else:
        parsed_conditions = []

    generated_files: list[tuple[Path, Path]] = []

    for v_id in target_variants:
        tmpl = available[v_id]
        v_offset = zlib.crc32(v_id.encode("utf-8")) % 10000
        for idx in range(count):
            sample_seed = seed + idx * 1000 + v_offset
            base_name = f"{v_id}_{idx:04d}"

            img, label = generate_single_sample(
                tmpl,
                seed=sample_seed,
                composite=composite,
                jitter=jitter,
            )

            # Record baseline metadata
            label["base_image_id"] = base_name
            label["condition"] = None
            label["severity"] = None

            img_filename = f"{base_name}.png"
            json_filename = f"{base_name}.json"
            img_file = out_path / img_filename
            json_file = out_path / json_filename

            label["image_path"] = img_filename

            # Save PNG without non-deterministic metadata chunks
            img.save(img_file, format="PNG", optimize=False)

            with open(json_file, "w", encoding="utf-8") as f:
                json.dump(label, f, indent=2, sort_keys=True)

            generated_files.append((img_file, json_file))

            # Apply parameterized degradation conditions if requested
            for cond_name, sev in parsed_conditions:
                cond_seed = sample_seed + zlib.crc32(f"{cond_name}_{sev}".encode()) % 50000
                cond_img, cond_label = apply_condition(
                    img,
                    label,
                    condition=cond_name,
                    severity=sev,
                    seed=cond_seed,
                )

                cond_base_name = f"{base_name}_{cond_name}_s{sev}"
                cond_img_filename = f"{cond_base_name}.png"
                cond_json_filename = f"{cond_base_name}.json"

                cond_img_file = out_path / cond_img_filename
                cond_json_file = out_path / cond_json_filename

                cond_label["image_path"] = cond_img_filename

                cond_img.save(cond_img_file, format="PNG", optimize=False)

                with open(cond_json_file, "w", encoding="utf-8") as f:
                    json.dump(cond_label, f, indent=2, sort_keys=True)

                generated_files.append((cond_img_file, cond_json_file))

    return generated_files


def main() -> None:
    """CLI entry point for python -m ml.generate."""
    parser = argparse.ArgumentParser(
        description="Generate synthetic Philippine ID and payment card benchmark datasets."
    )
    subparsers = parser.add_subparsers(dest="subcommand")

    # Full train/val/test dataset generator subcommand
    dataset_parser = subparsers.add_parser(
        "dataset",
        help="Build a complete train/val/test benchmark dataset from a YAML config file.",
    )
    dataset_parser.add_argument(
        "--config",
        type=str,
        required=True,
        help="Path to YAML configuration file.",
    )
    dataset_parser.add_argument(
        "--out",
        type=str,
        default=None,
        help="Optional destination directory overriding YAML config.",
    )

    # Variant-level options for backward compatibility
    parser.add_argument(
        "--variant",
        type=str,
        default="philsys-card",
        help="Template variant ID (or 'all' for all 6 D3 templates).",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=1,
        help="Number of images to generate per variant (default: 1).",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Deterministic integer seed (default: 42).",
    )
    parser.add_argument(
        "--out",
        type=str,
        default="data/synthetic",
        help="Destination directory for output images and JSON labels (default: data/synthetic).",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Render clean uncomposited cards without background rotation.",
    )
    parser.add_argument(
        "--jitter",
        action="store_true",
        help="Apply layout jitter (field offset, font size, print offset) at render time.",
    )
    parser.add_argument(
        "--conditions",
        type=str,
        default=None,
        help="Apply named image conditions (e.g. 'all', 'blur,rotation', 'blur:1,perspective:2').",
    )

    args = parser.parse_args()

    try:
        if args.subcommand == "dataset":
            from ml.dataset import build_full_dataset

            res = build_full_dataset(
                config_source=args.config,
                out_dir_override=args.out,
            )
            print(
                f"Generated benchmark dataset with {res['total_samples']} samples "
                f"in {res['out_dir']} (manifest: {res['manifest_path']})"
            )
        else:
            files = generate_dataset(
                variant=args.variant,
                count=args.count,
                seed=args.seed,
                out_dir=args.out,
                composite=not args.clean,
                jitter=args.jitter,
                conditions=args.conditions,
            )
            print(f"Generated {len(files)} synthetic card samples under {args.out}")
    except Exception as e:
        print(f"Error during generation: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
