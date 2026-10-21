"""Pytest test suite for synthetic Philippine ID and payment card generator.

Validates all Issue #5 acceptance criteria:
1. Renders every D3 template variant with fictional Faker fil_PH values and formatted IDs.
2. Payment card numbers strictly originate from published test ranges and pass Luhn check.
3. Every card carries a visible diagonal 'SPECIMEN – NOT VALID' watermark.
4. Cards are composited onto backgrounds (table, hand-held, screenshot) with pixel polygons.
5. The same seed gives byte-identical PNG and JSON output.
6. Clean rendered card field crops achieve >= 95% OCR exact match over a sample of 50 fields.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

import pytesseract
import pytest
from PIL import Image

from ml.composite import composite_card
from ml.data_generator import SyntheticDataGenerator
from ml.fonts import (
    BUNDLED_BOLD_FONT,
    BUNDLED_REGULAR_FONT,
    get_font,
    resolve_font_path,
)
from ml.generate import (
    generate_dataset,
    generate_single_sample,
    get_available_templates,
)
from ml.payment_card import (
    PUBLISHED_TEST_CARD_PREFIXES,
    PUBLISHED_TEST_PANS,
    generate_test_card_number,
    is_luhn_valid,
)
from ml.renderer import _get_bounding_box, render_card

REQUIRED_VARIANTS: set[str] = {
    "philsys-card",
    "philsys-ephilid",
    "lto-dl-current",
    "lto-dl-previous",
    "payment-card-front",
    "payment-card-back",
}


@pytest.fixture(scope="session")
def templates() -> dict[str, dict[str, Any]]:
    """Fixture providing loaded template dictionaries for all variants."""
    available = get_available_templates()
    for variant in REQUIRED_VARIANTS:
        assert variant in available, f"Template {variant} missing from available templates"
    return available


def test_renders_every_d3_template_variant(templates: dict[str, dict[str, Any]]) -> None:
    """Verify that every D3 template variant renders successfully with all fields."""
    for variant_id in REQUIRED_VARIANTS:
        tmpl = templates[variant_id]
        img, label = generate_single_sample(tmpl, seed=100 + len(variant_id), composite=True)

        assert isinstance(img, Image.Image)
        assert img.width == 1600
        assert img.height == 1200

        assert label["variant"] == variant_id
        assert label["document_class"] == tmpl["document_class"]
        assert len(label["card_corners"]) == 4

        # Verify all template fields are represented in ground truth
        expected_types = {f["type"] for f in tmpl["fields"]}
        produced_types = {f["type"] for f in label["fields"]}
        assert expected_types == produced_types, f"Mismatch in field types for {variant_id}"


def test_fictional_values_and_id_number_formats(templates: dict[str, dict[str, Any]]) -> None:
    """Verify generated values match required demographic and official ID format patterns."""
    data_gen = SyntheticDataGenerator(seed=777)

    # Test PhilSys format
    philsys_tmpl = templates["philsys-card"]
    philsys_fields = data_gen.generate_fields_for_template(philsys_tmpl)
    assert re.match(r"^\d{4}-\d{4}-\d{4}-\d{4}$", philsys_fields["pcn"])
    assert re.match(r"^\d{4}/\d{2}/\d{2}$", philsys_fields["date_of_birth"])
    assert len(philsys_fields["full_name"]) > 5
    assert len(philsys_fields["address"]) > 8

    # Test LTO format
    lto_tmpl = templates["lto-dl-current"]
    lto_fields = data_gen.generate_fields_for_template(lto_tmpl)
    assert re.match(r"^[A-Z]\d{2}-\d{2}-\d{6}$", lto_fields["license_number"])
    assert lto_fields["sex"] in ("M", "F")
    assert re.match(r"^\d\.\d{2} m$", lto_fields["height"])
    assert re.match(r"^\d{2} kg$", lto_fields["weight"])


def test_payment_card_published_test_ranges_and_luhn() -> None:
    """Verify payment cards originate strictly from published test ranges and pass Luhn."""
    import random

    rng = random.Random(12345)

    for _ in range(50):
        card_num = generate_test_card_number(rng)
        digits_only = card_num.replace(" ", "")

        assert len(digits_only) == 16, f"Invalid PAN length: {digits_only}"
        assert digits_only in PUBLISHED_TEST_PANS, (
            f"Card number {card_num} not in published test PAN list"
        )
        assert any(
            digits_only.startswith(prefix) for prefix in PUBLISHED_TEST_CARD_PREFIXES
        ), f"Card number {card_num} does not start with any published test prefix"
        assert is_luhn_valid(digits_only), f"Card number {card_num} failed Luhn checksum"


def test_visible_diagonal_specimen_watermark(templates: dict[str, dict[str, Any]]) -> None:
    """Verify clean rendered card image carries the diagonal SPECIMEN – NOT VALID watermark."""
    tmpl = templates["philsys-card"]
    data_gen = SyntheticDataGenerator(seed=42)
    vals = data_gen.generate_fields_for_template(tmpl)
    card_img, _ = render_card(tmpl, vals, seed=42)

    # Watermark text is drawn across card center with red tint
    # Verify presence of reddish watermark pixels in the center region
    w, h = card_img.width, card_img.height
    center_crop = card_img.crop((w // 4, h // 4, 3 * w // 4, 3 * h // 4))
    # Check for reddish tinted pixels where Red is higher than Green and Blue
    pixels = center_crop.load()
    has_watermark_pixels = False
    for x in range(center_crop.width):
        for y in range(center_crop.height):
            r, g, b = pixels[x, y]
            if r > g + 25 and r > b + 25:
                has_watermark_pixels = True
                break
        if has_watermark_pixels:
            break

    assert has_watermark_pixels, "Watermark reddish pixels not detected in card center"


def test_background_compositing_and_polygons(templates: dict[str, dict[str, Any]]) -> None:
    """Verify compositing on table, hand-held, and screenshot backgrounds with valid polygons."""
    tmpl = templates["lto-dl-current"]
    data_gen = SyntheticDataGenerator(seed=999)
    vals = data_gen.generate_fields_for_template(tmpl)
    clean_img, clean_fields = render_card(tmpl, vals, seed=999)

    for bg_type in ("table", "hand-held", "screenshot_frame"):
        comp_img, corners, fields = composite_card(
            clean_img,
            clean_fields,
            seed=999,
            bg_type=bg_type,
        )

        assert comp_img.size == (1600, 1200)

        # Verify all corner points reside within canvas bounds
        for x, y in corners:
            assert 0 <= x < 1600, f"Corner x={x} out of canvas bounds"
            assert 0 <= y < 1200, f"Corner y={y} out of canvas bounds"

        # Verify all field polygon vertices reside within canvas bounds
        for f in fields:
            for x, y in f["polygon"]:
                assert 0 <= x < 1600, f"Field point x={x} out of canvas bounds"
                assert 0 <= y < 1200, f"Field point y={y} out of canvas bounds"


def test_same_seed_gives_byte_identical_output(tmp_path: Path) -> None:
    """Verify that running generation with the same seed produces byte-identical files."""
    dir1 = tmp_path / "run1"
    dir2 = tmp_path / "run2"

    files1 = generate_dataset(variant="philsys-card", count=2, seed=42, out_dir=dir1)
    files2 = generate_dataset(variant="philsys-card", count=2, seed=42, out_dir=dir2)

    assert len(files1) == 2
    assert len(files2) == 2

    for (img1, json1), (img2, json2) in zip(files1, files2, strict=False):
        hash_img1 = hashlib.sha256(img1.read_bytes()).hexdigest()
        hash_img2 = hashlib.sha256(img2.read_bytes()).hexdigest()
        assert hash_img1 == hash_img2, f"PNG images differ for same seed: {img1} vs {img2}"

        hash_json1 = hashlib.sha256(json1.read_bytes()).hexdigest()
        hash_json2 = hashlib.sha256(json2.read_bytes()).hexdigest()
        assert hash_json1 == hash_json2, f"JSON labels differ for same seed: {json1} vs {json2}"


def test_ocr_exact_field_match_sample_of_50(templates: dict[str, dict[str, Any]]) -> None:
    """Verify label polygons match rendered text with >= 95% exact OCR match on 50 fields.

    Renders clean card images, crops to exact field bounding boxes, runs Tesseract OCR,
    and tests that at least 95% of text fields match the ground-truth value exactly.
    """
    matches = 0
    total = 0

    def normalize(text: str) -> str:
        # Standardize units spacing and collapse whitespace
        text = re.sub(r"(\d)\s*(m|kg)\b", r"\1 \2", text)
        return re.sub(r"\s+", " ", text).strip()

    for seed in range(15):
        for variant_id in sorted(REQUIRED_VARIANTS):
            tmpl = templates[variant_id]
            data_gen = SyntheticDataGenerator(seed=seed)
            vals = data_gen.generate_fields_for_template(tmpl)
            card_img, fields = render_card(tmpl, vals, seed=seed)

            for f in fields:
                ftype = f["type"]
                # Skip non-textual graphic fields
                if ftype in ("face_photo", "signature", "qr_code"):
                    continue

                val = f["value"]
                poly = f["polygon"]
                min_x, min_y, max_x, max_y = _get_bounding_box(poly)

                crop = card_img.crop((
                    max(0, min_x),
                    max(0, min_y),
                    min(card_img.width, max_x),
                    min(card_img.height, max_y),
                ))

                psm = 6 if "\n" in val else 7
                ocr_text = pytesseract.image_to_string(crop, config=f"--psm {psm}").strip()

                if normalize(val) == normalize(ocr_text):
                    matches += 1

                total += 1
                if total >= 50:
                    break
            if total >= 50:
                break
        if total >= 50:
            break

    accuracy = (matches / total) * 100
    print(f"OCR Match Evaluation: {matches}/{total} ({accuracy:.1f}%)")
    assert total == 50
    assert accuracy >= 95.0, f"OCR accuracy {accuracy:.1f}% is below 95% threshold"


def test_cli_generation_all_variants(tmp_path: Path) -> None:
    """Verify CLI generation with --variant all produces expected counts and JSON labels."""
    out_dir = tmp_path / "all_out"
    files = generate_dataset(variant="all", count=1, seed=88, out_dir=out_dir)

    assert len(files) == len(REQUIRED_VARIANTS)
    for img_path, json_path in files:
        assert img_path.exists()
        assert json_path.exists()

        with open(json_path, encoding="utf-8") as f:
            data = json.load(f)
            assert "class" in data
            assert "variant" in data
            assert "card_corners" in data
            assert "fields" in data
            assert len(data["fields"]) >= 3


def test_cli_subprocess_byte_identical_across_pythonhashseeds(tmp_path: Path) -> None:
    """Verify CLI produces byte-identical outputs across subprocesses with different PYTHONHASHSEED.

    Ensures CLI generation is completely deterministic across independent processes
    regardless of process-level hash salting.
    """
    import os
    import subprocess
    import sys

    dir1 = tmp_path / "hashseed_0"
    dir2 = tmp_path / "hashseed_12345"

    env1 = {**os.environ, "PYTHONHASHSEED": "0"}
    env2 = {**os.environ, "PYTHONHASHSEED": "12345"}

    cmd_base = [
        sys.executable,
        "-m",
        "ml.generate",
        "--variant",
        "all",
        "--count",
        "1",
        "--seed",
        "42",
    ]

    subprocess.run(
        [*cmd_base, "--out", str(dir1)],
        env=env1,
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        [*cmd_base, "--out", str(dir2)],
        env=env2,
        check=True,
        capture_output=True,
        text=True,
    )

    files1 = sorted(dir1.glob("*.png"))
    files2 = sorted(dir2.glob("*.png"))

    assert len(files1) == len(REQUIRED_VARIANTS)
    assert len(files2) == len(REQUIRED_VARIANTS)

    for p1, p2 in zip(files1, files2, strict=False):
        assert p1.name == p2.name
        assert p1.read_bytes() == p2.read_bytes(), (
            f"CLI output differs under different PYTHONHASHSEED for {p1.name}"
        )


def test_bundled_font_resolution_and_determinism() -> None:
    """Verify bundled OFL Liberation Sans font resolves and loads reliably.

    Ensures font resolution is deterministic across different host environments
    by preferring bundled fonts over arbitrary system fonts.
    """
    assert BUNDLED_REGULAR_FONT.is_file(), f"Missing regular font: {BUNDLED_REGULAR_FONT}"
    assert BUNDLED_BOLD_FONT.is_file(), f"Missing bold font: {BUNDLED_BOLD_FONT}"

    resolved_reg = resolve_font_path(bold=False)
    resolved_bold = resolve_font_path(bold=True)

    assert resolved_reg == str(BUNDLED_REGULAR_FONT)
    assert resolved_bold == str(BUNDLED_BOLD_FONT)

    font_reg = get_font(size=20, bold=False)
    font_bold = get_font(size=20, bold=True)

    assert font_reg is not None
    assert font_bold is not None


