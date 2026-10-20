"""Validator for card design variant templates.

Validates template JSON files against templates/schema.json, ensures polygon coordinates
stay within the unit square [0, 1], verifies that sensitive fields do not overlap left-visible
fields, and checks that all §8.1 redacted fields are represented for each document class.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import jsonschema

# Canonical redacted fields required per document class according to SECURE_GALLERY.md §8.1.
# PhilSys: PCN/PSN, full name, date of birth, address, face photo, QR code, signature.
# LTO: License number, name, date of birth, address, face photo, signature.
# Payment card: Card number, cardholder name, expiry date, CVV (if the back is shown).
EXPECTED_REDACTED_FIELDS_PER_CLASS: dict[str, set[str]] = {
    "philsys": {
        "pcn",  # or pcn_psn
        "full_name",  # or name
        "date_of_birth",
        "address",
        "face_photo",
        "qr_code",
        "signature",
    },
    "lto": {
        "license_number",
        "name",  # or full_name
        "date_of_birth",
        "address",
        "face_photo",
        "signature",
    },
    "payment_card": {
        "card_number",
        "cardholder_name",
        "expiry_date",
        "cvv",
    },
}

# Accepted type aliases to allow flexibility between schema and table names.
FIELD_TYPE_ALIASES: dict[str, str] = {
    "pcn_psn": "pcn",
    "name": "full_name",
    "full_name": "name",
}


def load_json(path: str | Path) -> dict[str, Any]:
    """Load and parse a JSON file from disk.

    Args:
        path: Path to the JSON file.

    Returns:
        Parsed JSON dictionary.
    """
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def validate_against_schema(
    template_data: dict[str, Any], schema_data: dict[str, Any]
) -> None:
    """Validate a template data dictionary against the JSON schema.

    Args:
        template_data: The card template data dictionary.
        schema_data: The JSON schema dictionary.

    Raises:
        jsonschema.ValidationError: If validation fails.
    """
    jsonschema.validate(instance=template_data, schema=schema_data)


def check_polygon_bounds(template_data: dict[str, Any]) -> list[str]:
    """Verify that all polygon vertices in all fields lie within [0.0, 1.0].

    Args:
        template_data: The card template data dictionary.

    Returns:
        A list of error message strings describing any boundary violations.
    """
    errors: list[str] = []
    fields = template_data.get("fields", [])
    for idx, field in enumerate(fields):
        name = field.get("name", f"field[{idx}]")
        polygon = field.get("polygon", [])
        if not isinstance(polygon, list) or len(polygon) < 3:
            errors.append(f"Field '{name}': polygon must contain at least 3 vertices.")
            continue
        for pt_idx, pt in enumerate(polygon):
            if not isinstance(pt, (list, tuple)) or len(pt) != 2:
                errors.append(
                    f"Field '{name}': vertex {pt_idx} must be a 2-element [x, y] list."
                )
                continue
            x, y = pt[0], pt[1]
            if not (0.0 <= x <= 1.0) or not (0.0 <= y <= 1.0):
                errors.append(
                    f"Field '{name}': vertex {pt_idx} ({x}, {y}) is outside the unit square [0, 1]."
                )
    return errors


def polygon_bounding_box(
    polygon: list[list[float]],
) -> tuple[float, float, float, float]:
    """Compute the axis-aligned bounding box (min_x, min_y, max_x, max_y) of a polygon.

    Args:
        polygon: List of [x, y] coordinates.

    Returns:
        Tuple of (min_x, min_y, max_x, max_y).
    """
    xs = [pt[0] for pt in polygon]
    ys = [pt[1] for pt in polygon]
    return min(xs), min(ys), max(xs), max(ys)


def boxes_overlap(
    box1: tuple[float, float, float, float],
    box2: tuple[float, float, float, float],
    epsilon: float = 1e-5,
) -> bool:
    """Check if two axis-aligned bounding boxes overlap with non-trivial area.

    Args:
        box1: (min_x, min_y, max_x, max_y)
        box2: (min_x, min_y, max_x, max_y)
        epsilon: Minimum intersection width/height threshold to avoid boundary-touching false positives.

    Returns:
        True if the boxes overlap by more than epsilon in both dimensions.
    """
    b1_min_x, b1_min_y, b1_max_x, b1_max_y = box1
    b2_min_x, b2_min_y, b2_max_x, b2_max_y = box2

    inter_w = min(b1_max_x, b2_max_x) - max(b1_min_x, b2_min_x)
    inter_h = min(b1_max_y, b2_max_y) - max(b1_min_y, b2_min_y)

    return inter_w > epsilon and inter_h > epsilon


def check_no_overlap(template_data: dict[str, Any]) -> list[str]:
    """Verify that sensitive fields (sensitive: True) do not overlap left-visible fields (sensitive: False).

    According to SECURE_GALLERY.md §8.1 and the issue acceptance criteria, fields marked
    left visible (such as headings, logos, sex, height, weight) must not be occluded by
    the redaction regions of sensitive fields.

    Args:
        template_data: The card template data dictionary.

    Returns:
        A list of error message strings describing any illegal overlaps.
    """
    errors: list[str] = []
    fields = template_data.get("fields", [])

    sensitive_fields = [f for f in fields if f.get("sensitive") is True]
    visible_fields = [f for f in fields if f.get("sensitive") is False]

    for s_field in sensitive_fields:
        s_box = polygon_bounding_box(s_field["polygon"])
        for v_field in visible_fields:
            v_box = polygon_bounding_box(v_field["polygon"])
            if boxes_overlap(s_box, v_box):
                errors.append(
                    f"Template '{template_data.get('id', 'unknown')}': Sensitive field "
                    f"'{s_field.get('name')}' overlaps left-visible field '{v_field.get('name')}'."
                )
    return errors


def check_redacted_fields_per_class(
    templates: list[dict[str, Any]],
) -> dict[str, list[str]]:
    """Check that every redacted field listed in SECURE_GALLERY.md §8.1 is present for its document class.

    Across the set of templates for a given class (e.g. front and back templates),
    all sensitive fields designated for redaction in §8.1 must be defined.

    Args:
        templates: List of parsed template dictionaries.

    Returns:
        Dictionary mapping class name to list of missing field names (empty if all present).
    """
    class_sensitive_types: dict[str, set[str]] = {}

    for template in templates:
        doc_class = template.get("document_class")
        # Normalize class aliases (e.g. philsys_id -> philsys, lto_driver_license -> lto)
        if doc_class == "philsys_id":
            doc_class = "philsys"
        elif doc_class == "lto_driver_license":
            doc_class = "lto"

        if not doc_class:
            continue

        if doc_class not in class_sensitive_types:
            class_sensitive_types[doc_class] = set()

        for field in template.get("fields", []):
            if field.get("sensitive") is True:
                ftype = field.get("type", "")
                class_sensitive_types[doc_class].add(ftype)
                # Also add aliases for matching
                if ftype in FIELD_TYPE_ALIASES:
                    class_sensitive_types[doc_class].add(FIELD_TYPE_ALIASES[ftype])

    missing_per_class: dict[str, list[str]] = {}

    for doc_class, expected_fields in EXPECTED_REDACTED_FIELDS_PER_CLASS.items():
        present_types = class_sensitive_types.get(doc_class, set())
        missing: list[str] = []
        for expected in expected_fields:
            if expected not in present_types:
                # Check if alias is present
                alias = FIELD_TYPE_ALIASES.get(expected)
                if not (alias and alias in present_types):
                    missing.append(expected)
        if missing:
            missing_per_class[doc_class] = missing

    return missing_per_class


def validate_all_templates(
    templates_dir: str | Path,
) -> tuple[bool, list[str]]:
    """Perform complete validation of all template files in a directory.

    Args:
        templates_dir: Path to directory containing schema.json and template JSON files.

    Returns:
        Tuple of (is_valid, list_of_error_strings).
    """
    templates_path = Path(templates_dir)
    schema_path = templates_path / "schema.json"
    if not schema_path.exists():
        return False, [f"Schema not found at {schema_path}"]

    schema = load_json(schema_path)
    template_files = sorted(
        [
            p
            for p in templates_path.glob("*.json")
            if p.name != "schema.json" and not p.name.startswith(".")
        ]
    )

    if not template_files:
        return False, ["No template JSON files found to validate."]

    all_errors: list[str] = []
    loaded_templates: list[dict[str, Any]] = []

    for template_file in template_files:
        try:
            data = load_json(template_file)
            loaded_templates.append(data)
        except (json.JSONDecodeError, OSError) as exc:
            all_errors.append(f"Failed to read {template_file.name}: {exc}")
            continue

        try:
            validate_against_schema(data, schema)
        except jsonschema.ValidationError as err:
            all_errors.append(
                f"Schema validation failed for {template_file.name}: {err.message}"
            )

        bounds_errors = check_polygon_bounds(data)
        for err_msg in bounds_errors:
            all_errors.append(f"{template_file.name}: {err_msg}")

        overlap_errors = check_no_overlap(data)
        for err_msg in overlap_errors:
            all_errors.append(f"{template_file.name}: {err_msg}")

    missing_fields = check_redacted_fields_per_class(loaded_templates)
    for doc_class, missing in missing_fields.items():
        all_errors.append(
            f"Document class '{doc_class}' is missing required §8.1 redacted fields: {missing}"
        )

    return len(all_errors) == 0, all_errors


if __name__ == "__main__":
    import sys

    dir_to_check = Path(__file__).parent if "__file__" in globals() else Path(".")
    ok, errors = validate_all_templates(dir_to_check)
    if ok:
        print("All card templates validated successfully!")
        sys.exit(0)
    else:
        print("Validation errors encountered:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        sys.exit(1)
