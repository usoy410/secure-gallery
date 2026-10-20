"""Validator for card design variant templates.

Validates template JSON files against templates/schema.json, ensures polygon coordinates
stay within the unit square [0, 1], verifies that sensitive fields do not overlap left-visible
fields using exact polygon intersection testing, and enforces per-variant coverage of all
SECURE_GALLERY.md §8.1 redacted fields (accounting for explicit documented exemptions).
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

# Explicit documented exemptions per card template variant.
# Maps template variant ID to a dictionary of {field_type: rationale}.
# Why this exists:
# Two-sided documents (such as physical payment cards) separate sensitive fields across
# front and back surfaces. The payment-card-front template contains card number, cardholder
# name, and expiry date, but is exempt from CVV because the CVV is printed on the back.
# Similarly, payment-card-back contains CVV and is exempt from the front-side fields.
# All exemptions must be explicitly listed and justified here; variants without exemptions
# must provide 100% coverage of their document class's required redacted fields.
VARIANT_FIELD_EXEMPTIONS: dict[str, dict[str, str]] = {
    "payment-card-front": {
        "cvv": "CVV/CVC is located on the reverse side of standard payment cards, covered by payment-card-back.",
    },
    "payment-card-back": {
        "card_number": "Primary Account Number (PAN) is on the front side of standard payment cards, covered by payment-card-front.",
        "cardholder_name": "Cardholder name is on the front side of standard payment cards, covered by payment-card-front.",
        "expiry_date": "Expiration date is on the front side of standard payment cards, covered by payment-card-front.",
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


def _cross_product_2d(
    p: list[float] | tuple[float, float],
    q: list[float] | tuple[float, float],
    r: list[float] | tuple[float, float],
) -> float:
    """Compute the 2D cross product of vectors pq and pr.

    Args:
        p: Origin point [x, y].
        q: First destination point [x, y].
        r: Second destination point [x, y].

    Returns:
        Signed area / orientation value (>0 counter-clockwise, <0 clockwise, 0 collinear).
    """
    return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])


def _segments_intersect_strict(
    p1: list[float] | tuple[float, float],
    p2: list[float] | tuple[float, float],
    p3: list[float] | tuple[float, float],
    p4: list[float] | tuple[float, float],
    eps: float = 1e-9,
) -> bool:
    """Check if two open line segments (p1-p2 and p3-p4) strictly intersect in their interiors.

    Collinear segments or segments touching only at endpoints return False.
    """
    d1 = _cross_product_2d(p3, p4, p1)
    d2 = _cross_product_2d(p3, p4, p2)
    d3 = _cross_product_2d(p1, p2, p3)
    d4 = _cross_product_2d(p1, p2, p4)

    return ((d1 > eps and d2 < -eps) or (d1 < -eps and d2 > eps)) and (
        (d3 > eps and d4 < -eps) or (d3 < -eps and d4 > eps)
    )


def point_in_polygon_strict(
    pt: list[float] | tuple[float, float],
    polygon: list[list[float]],
    eps: float = 1e-9,
) -> bool:
    """Check if a point lies strictly within the interior of a polygon (not on its boundary).

    Uses the standard ray-casting algorithm. Points lying on an edge or vertex return False.

    Args:
        pt: The point [x, y] to test.
        polygon: List of polygon vertices [[x1, y1], [x2, y2], ...].
        eps: Numerical tolerance.

    Returns:
        True if the point is strictly inside the polygon interior.
    """
    x, y = pt[0], pt[1]
    n = len(polygon)
    inside = False

    for i in range(n):
        p1 = polygon[i]
        p2 = polygon[(i + 1) % n]

        # Check if point lies directly on boundary segment p1-p2
        cross = _cross_product_2d(p1, p2, pt)
        if abs(cross) < eps:
            min_x, max_x = min(p1[0], p2[0]), max(p1[0], p2[0])
            min_y, max_y = min(p1[1], p2[1]), max(p1[1], p2[1])
            if (min_x - eps <= x <= max_x + eps) and (min_y - eps <= y <= max_y + eps):
                return False  # On boundary, not strictly in interior

        if (p1[1] > y) != (p2[1] > y):
            x_inters = (y - p1[1]) * (p2[0] - p1[0]) / (p2[1] - p1[1]) + p1[0]
            if x_inters > x + eps:
                inside = not inside

    return inside


def polygons_overlap(
    poly1: list[list[float]],
    poly2: list[list[float]],
    epsilon: float = 1e-7,
) -> bool:
    """Test whether two 2D polygons overlap with non-zero 2D intersection area.

    Why this is used instead of pure bounding-box testing:
    Bounding-box overlap checks produce false positives for non-rectangular or angled
    polygons whose axis-aligned bounds intersect while their actual polygon shapes do
    not touch. This function implements a complete polygon-overlap test:
    1. Early rejection using axis-aligned bounding boxes.
    2. Strict edge intersection detection (transverse crossings).
    3. Interior containment: tests vertices and centroid to catch cases where one polygon
       lies entirely inside another.

    Args:
        poly1: List of 2D vertices for the first polygon.
        poly2: List of 2D vertices for the second polygon.
        epsilon: Numerical tolerance.

    Returns:
        True if the two polygons share a positive 2D area, False otherwise.
    """
    box1 = polygon_bounding_box(poly1)
    box2 = polygon_bounding_box(poly2)
    if not boxes_overlap(box1, box2, epsilon=epsilon):
        return False

    n1 = len(poly1)
    n2 = len(poly2)

    # 1. Edge crossing check
    for i in range(n1):
        s1a = poly1[i]
        s1b = poly1[(i + 1) % n1]
        for j in range(n2):
            s2a = poly2[j]
            s2b = poly2[(j + 1) % n2]
            if _segments_intersect_strict(s1a, s1b, s2a, s2b, eps=epsilon):
                return True

    # 2. poly1 inside poly2
    for pt in poly1:
        if point_in_polygon_strict(pt, poly2, eps=epsilon):
            return True

    c1 = [sum(p[0] for p in poly1) / n1, sum(p[1] for p in poly1) / n1]
    if point_in_polygon_strict(c1, poly2, eps=epsilon):
        return True

    # 3. poly2 inside poly1
    for pt in poly2:
        if point_in_polygon_strict(pt, poly1, eps=epsilon):
            return True

    c2 = [sum(p[0] for p in poly2) / n2, sum(p[1] for p in poly2) / n2]
    return point_in_polygon_strict(c2, poly1, eps=epsilon)


def check_no_overlap(template_data: dict[str, Any]) -> list[str]:
    """Verify that sensitive fields (sensitive: True) do not overlap left-visible fields (sensitive: False).

    According to SECURE_GALLERY.md §8.1 and the issue acceptance criteria, fields marked
    left visible (such as headings, logos, sex, height, weight) must not be occluded by
    the redaction regions of sensitive fields. Uses exact polygon overlap testing to prevent
    false positives on non-rectangular or diagonal polygon boundaries.

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
        for v_field in visible_fields:
            if polygons_overlap(s_field["polygon"], v_field["polygon"]):
                errors.append(
                    f"Template '{template_data.get('id', 'unknown')}': Sensitive field "
                    f"'{s_field.get('name')}' overlaps left-visible field '{v_field.get('name')}'."
                )
    return errors


def check_redacted_fields_per_variant(
    template: dict[str, Any],
) -> list[str]:
    """Check that a specific card template variant covers all required §8.1 redacted fields,
    accounting for explicit documented exemptions in VARIANT_FIELD_EXEMPTIONS.

    Why per-variant coverage with explicit exemptions is required:
    Unioning fields across variants can mask omissions in an individual card template
    (e.g. a physical card missing QR code simply because a paper variant provides it).
    Per-variant verification ensures every variant provides complete coverage unless
    an explicit documented exemption justifies the omission (e.g. CVV on payment card back).

    Args:
        template: The parsed card template dictionary.

    Returns:
        List of missing field names (empty if all expected fields are covered or exempted).
    """
    variant_id = template.get("id", "")
    doc_class = template.get("document_class", "")

    expected_fields = EXPECTED_REDACTED_FIELDS_PER_CLASS.get(doc_class)
    if expected_fields is None:
        return [f"Unknown or unsupported document class '{doc_class}'"]

    # Gather sensitive field types present in this variant
    present_types: set[str] = set()
    for field in template.get("fields", []):
        if field.get("sensitive") is True:
            ftype = field.get("type", "")
            present_types.add(ftype)
            if ftype in FIELD_TYPE_ALIASES:
                present_types.add(FIELD_TYPE_ALIASES[ftype])

    # Lookup explicit exemptions for this variant
    exemptions = VARIANT_FIELD_EXEMPTIONS.get(variant_id, {})

    missing: list[str] = []
    for expected in sorted(expected_fields):
        if expected in exemptions:
            continue
        if expected not in present_types:
            alias = FIELD_TYPE_ALIASES.get(expected)
            if not (alias and alias in present_types):
                missing.append(expected)

    return missing


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

    Validates schema conformance, coordinate bounds, non-overlap between sensitive and
    left-visible fields, per-variant coverage with documented exemptions, and class-level coverage.

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

        # Check per-variant coverage with exemptions
        variant_missing = check_redacted_fields_per_variant(data)
        for missing_field in variant_missing:
            all_errors.append(
                f"{template_file.name} (variant '{data.get('id')}'): Missing required §8.1 redacted field '{missing_field}' without exemption."
            )

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
