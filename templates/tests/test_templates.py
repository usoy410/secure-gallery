"""Pytest suite for card templates and schema.

Validates that:
- Every template conforms to templates/schema.json.
- Templates for PhilSys, ePhilID, LTO (current & previous), and payment cards (front & back) exist.
- All polygon vertices stay strictly within the unit square [0, 1].
- Sensitive fields do not overlap left-visible fields.
- Every redacted field listed in SECURE_GALLERY.md §8.1 is present for its document class.
- Aspect ratios are positive numbers matching physical standards.
- templates/SOURCES.md documents all public specimen sources.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

# Ensure repository root is on sys.path even when pytest is invoked without python -m
TEMPLATES_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = TEMPLATES_DIR.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

import jsonschema
import pytest

from templates.validate import (
    check_no_overlap,
    check_polygon_bounds,
    check_redacted_fields_per_class,
    load_json,
    validate_against_schema,
)

TEMPLATES_DIR = Path(__file__).resolve().parent.parent
SCHEMA_PATH = TEMPLATES_DIR / "schema.json"
SOURCES_PATH = TEMPLATES_DIR / "SOURCES.md"

REQUIRED_TEMPLATE_IDS = {
    "philsys-card",
    "philsys-ephilid",
    "lto-dl-current",
    "lto-dl-previous",
    "payment-card-front",
    "payment-card-back",
}


@pytest.fixture(scope="session")
def schema_data() -> dict[str, Any]:
    """Fixture providing the parsed schema.json."""
    assert SCHEMA_PATH.exists(), f"Schema file not found at {SCHEMA_PATH}"
    return load_json(SCHEMA_PATH)


@pytest.fixture(scope="session")
def template_files() -> list[Path]:
    """Fixture providing all card template JSON file paths."""
    files = sorted(
        [
            p
            for p in TEMPLATES_DIR.glob("*.json")
            if p.name != "schema.json" and not p.name.startswith(".")
        ]
    )
    assert len(files) > 0, "No card template JSON files found in templates directory."
    return files


@pytest.fixture(scope="session")
def loaded_templates(template_files: list[Path]) -> dict[str, dict[str, Any]]:
    """Fixture providing a dictionary of variant_id -> template_data."""
    templates = {}
    for p in template_files:
        data = load_json(p)
        variant_id = data.get("id")
        assert variant_id, f"Template file {p.name} missing 'id' property."
        templates[variant_id] = data
    return templates


def test_schema_itself_is_valid(schema_data: dict[str, Any]) -> None:
    """Verify that templates/schema.json is a valid JSON schema (Draft 7)."""
    validator_cls = jsonschema.validators.validator_for(schema_data)
    validator_cls.check_schema(schema_data)


def test_required_variants_exist(loaded_templates: dict[str, dict[str, Any]]) -> None:
    """Ensure all variants required by Issue #3 acceptance criteria are present."""
    present_ids = set(loaded_templates.keys())
    missing = REQUIRED_TEMPLATE_IDS - present_ids
    assert not missing, f"Missing required card template variants: {missing}"


def test_templates_against_schema(
    template_files: list[Path], schema_data: dict[str, Any]
) -> None:
    """Validate each template JSON file against templates/schema.json."""
    for template_file in template_files:
        data = load_json(template_file)
        try:
            validate_against_schema(data, schema_data)
        except jsonschema.ValidationError as err:
            pytest.fail(f"Template {template_file.name} failed schema validation: {err.message}")


def test_polygons_inside_unit_square(
    template_files: list[Path],
) -> None:
    """Check that all polygon coordinates stay inside [0.0, 1.0]."""
    all_bounds_errors = []
    for template_file in template_files:
        data = load_json(template_file)
        errors = check_polygon_bounds(data)
        for err in errors:
            all_bounds_errors.append(f"{template_file.name}: {err}")
    assert not all_bounds_errors, "Polygon boundary errors found:\n" + "\n".join(all_bounds_errors)


def test_polygons_have_valid_points(
    template_files: list[Path],
) -> None:
    """Check that each field polygon has at least 3 points and valid non-empty area."""
    for template_file in template_files:
        data = load_json(template_file)
        for field in data.get("fields", []):
            poly = field["polygon"]
            assert len(poly) >= 3, f"{template_file.name}: field '{field['name']}' has < 3 vertices"
            xs = [p[0] for p in poly]
            ys = [p[1] for p in poly]
            # Width and height must be strictly positive
            assert max(xs) > min(xs), f"{template_file.name}: field '{field['name']}' has zero width"
            assert max(ys) > min(ys), f"{template_file.name}: field '{field['name']}' has zero height"


def test_sensitive_fields_do_not_overlap_left_visible(
    template_files: list[Path],
) -> None:
    """Verify that sensitive fields do not overlap left-visible fields."""
    all_overlap_errors = []
    for template_file in template_files:
        data = load_json(template_file)
        errors = check_no_overlap(data)
        all_overlap_errors.extend(errors)
    assert not all_overlap_errors, "Illegal field overlaps detected:\n" + "\n".join(all_overlap_errors)


def test_every_redacted_field_in_section_8_1_present_for_class(
    loaded_templates: dict[str, dict[str, Any]],
) -> None:
    """Ensure that every redacted field listed in SECURE_GALLERY.md §8.1 is present for its class."""
    template_list = list(loaded_templates.values())
    missing_fields = check_redacted_fields_per_class(template_list)
    assert not missing_fields, (
        f"Missing §8.1 redacted fields per document class: {missing_fields}"
    )


def test_aspect_ratios_positive_and_standard(
    loaded_templates: dict[str, dict[str, Any]],
) -> None:
    """Check that aspect ratios are positive numbers and near ID-1 standard (~1.5858)."""
    for variant_id, data in loaded_templates.items():
        ar = data["aspect_ratio"]
        assert isinstance(ar, (int, float)), f"{variant_id}: aspect_ratio must be numeric"
        assert ar > 0, f"{variant_id}: aspect_ratio must be positive"
        # Standard ID-1 card aspect ratio is 85.60 / 53.98 ~= 1.5858
        assert 1.4 <= ar <= 1.8, f"{variant_id}: aspect_ratio {ar} deviates significantly from card standard"


def test_sources_markdown_exists_and_documents_templates(
    loaded_templates: dict[str, dict[str, Any]],
) -> None:
    """Verify that templates/SOURCES.md exists, has content, and references each template."""
    assert SOURCES_PATH.exists(), f"SOURCES.md missing at {SOURCES_PATH}"
    content = SOURCES_PATH.read_text(encoding="utf-8")
    assert len(content.strip()) > 100, "SOURCES.md is unexpectedly empty or minimal"
    assert "2026-10-20" in content, "SOURCES.md missing access date"
    for variant_id in loaded_templates:
        assert variant_id in content, f"SOURCES.md does not document template variant '{variant_id}'"
