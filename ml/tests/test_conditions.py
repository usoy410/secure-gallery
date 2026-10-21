"""Test suite for image-quality degradation conditions and layout jitter.

Validates all Issue #6 acceptance criteria:
1. 8 conditions each with >= 3 severity levels: blur, glare, rotation, low resolution,
   perspective, JPEG compression, crop, occlusion.
2. Layout jitter applied at render time: field offset up to +/-2% card width,
   +/-5% font size, small print-offset shift.
3. Label polygons stay correct after geometric conditions: transformed polygons
   match re-rendered field masks with IoU >= 0.95.
4. The label records condition name, severity, and base image id.
5. CLI --conditions and --jitter integration.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from PIL import Image, ImageChops, ImageDraw

from ml.conditions import (
    CONDITION_NAMES,
    apply_condition,
    apply_crop,
    apply_perspective,
    apply_rotation,
    parse_conditions_arg,
)
from ml.data_generator import SyntheticDataGenerator
from ml.generate import (
    generate_dataset,
    generate_single_sample,
    get_available_templates,
)
from ml.jitter import (
    compute_field_offset,
    compute_font_size_jitter,
    compute_print_offset,
)
from ml.renderer import DEFAULT_CARD_WIDTH, render_card


@pytest.fixture(scope="session")
def sample_template() -> dict[str, Any]:
    """Provide loaded PhilSys template dictionary."""
    available = get_available_templates()
    assert "philsys-card" in available
    return available["philsys-card"]


def compute_mask_iou(mask1: Image.Image, mask2: Image.Image) -> float:
    """Compute Intersection over Union (IoU) between two binary Pillow masks."""
    m1 = mask1.point(lambda p: 255 if p > 127 else 0).convert("1")
    m2 = mask2.point(lambda p: 255 if p > 127 else 0).convert("1")
    inter = ImageChops.logical_and(m1, m2)
    union = ImageChops.logical_or(m1, m2)
    inter_count = sum(1 for p in inter.getdata() if p > 0)
    union_count = sum(1 for p in union.getdata() if p > 0)
    assert union_count > 0, "Both masks are empty; union count must be > 0 to compute IoU"
    return inter_count / union_count


# ---------------------------------------------------------------------------
# Criterion 1: All 8 conditions with >= 3 severity levels
# ---------------------------------------------------------------------------


def test_all_8_conditions_implemented() -> None:
    """Verify all 8 specified condition names are registered."""
    expected = {
        "blur",
        "glare",
        "rotation",
        "low_resolution",
        "perspective",
        "jpeg_compression",
        "crop",
        "occlusion",
    }
    assert set(CONDITION_NAMES) == expected


@pytest.mark.parametrize("cond_name", CONDITION_NAMES)
@pytest.mark.parametrize("severity", [1, 2, 3])
def test_each_condition_runs_all_severities(
    sample_template: dict[str, Any],
    cond_name: str,
    severity: int,
) -> None:
    """Verify each condition executes successfully across severities 1, 2, and 3."""
    img, label = generate_single_sample(sample_template, seed=100 + severity, composite=True)
    label["base_image_id"] = "sample_base_0001"

    out_img, out_label = apply_condition(
        img,
        label,
        condition=cond_name,
        severity=severity,
        seed=200 + severity,
    )

    assert isinstance(out_img, Image.Image)
    assert out_img.width > 0
    assert out_img.height > 0
    assert out_label["condition"] == cond_name
    assert out_label["severity"] == severity
    assert out_label["base_image_id"] == "sample_base_0001"


def test_invalid_condition_and_severity_raises(sample_template: dict[str, Any]) -> None:
    """Verify ValueError is raised on unrecognized condition or out-of-range severity."""
    img, label = generate_single_sample(sample_template, seed=42)

    with pytest.raises(ValueError, match="Unknown condition"):
        apply_condition(img, label, condition="nonexistent_condition", severity=1)

    with pytest.raises(ValueError, match="Invalid severity"):
        apply_condition(img, label, condition="blur", severity=99)


# ---------------------------------------------------------------------------
# Criterion 2: Layout jitter applied at render time
# ---------------------------------------------------------------------------


def test_layout_jitter_field_offset_within_2_percent() -> None:
    """Verify field displacement does not exceed +/- 2% of card width."""
    import random

    rng = random.Random(42)
    card_w = DEFAULT_CARD_WIDTH  # 1012 px
    max_allowed = int(round(card_w * 0.02))  # ~20 px

    for _ in range(100):
        dx, dy = compute_field_offset(rng, card_w, max_offset_ratio=0.02)
        assert abs(dx) <= max_allowed, f"Field dx {dx} exceeded 2% limit ({max_allowed})"
        assert abs(dy) <= max_allowed, f"Field dy {dy} exceeded 2% limit ({max_allowed})"


def test_layout_jitter_font_size_within_5_percent() -> None:
    """Verify font size jitter is constrained to +/- 5% of base font size."""
    import random

    rng = random.Random(777)
    base_sizes = [18, 20, 22, 24, 25]

    for base_sz in base_sizes:
        for _ in range(50):
            jittered_sz = compute_font_size_jitter(rng, base_sz, variation_ratio=0.05)
            # Allowed integer range around base_sz +/- 5%
            max_delta = max(1, int(round(base_sz * 0.05)))
            assert abs(jittered_sz - base_sz) <= max_delta + 1


def test_layout_jitter_print_offset_within_bounds() -> None:
    """Verify global print-offset shift remains subtle within card dimensions."""
    import random

    rng = random.Random(999)
    card_w = 1012
    card_h = 638
    max_dx = max(1, int(round(card_w * 0.008)))
    max_dy = max(1, int(round(card_h * 0.008)))

    for _ in range(50):
        pdx, pdy = compute_print_offset(rng, card_w, card_h)
        assert abs(pdx) <= max_dx
        assert abs(pdy) <= max_dy


def test_render_card_with_and_without_jitter(sample_template: dict[str, Any]) -> None:
    """Verify render_card with jitter=True shifts field polygons relative to jitter=False."""
    data_gen = SyntheticDataGenerator(seed=42)
    field_values = data_gen.generate_fields_for_template(sample_template)

    clean_img, clean_fields = render_card(sample_template, field_values, seed=42, jitter=False)
    jit_img, jit_fields = render_card(sample_template, field_values, seed=42, jitter=True)

    assert clean_img.size == jit_img.size
    assert len(clean_fields) == len(jit_fields)

    # Polygons should differ between jittered and clean rendering
    differences = 0
    for cf, jf in zip(clean_fields, jit_fields, strict=True):
        if cf["polygon"] != jf["polygon"]:
            differences += 1
    assert differences > 0, "Layout jitter produced no polygon shifts"


def test_jitter_same_seed_is_deterministic(sample_template: dict[str, Any]) -> None:
    """Verify render_card with jitter=True produces identical output for the same seed."""
    data_gen = SyntheticDataGenerator(seed=42)
    field_values = data_gen.generate_fields_for_template(sample_template)

    img1, fields1 = render_card(sample_template, field_values, seed=123, jitter=True)
    img2, fields2 = render_card(sample_template, field_values, seed=123, jitter=True)

    assert img1.tobytes() == img2.tobytes()
    assert fields1 == fields2


# ---------------------------------------------------------------------------
# Criterion 3: Label polygons stay correct after geometric conditions (IoU >= 0.95)
# ---------------------------------------------------------------------------


def test_rotation_polygons_match_transformed_mask_iou(sample_template: dict[str, Any]) -> None:
    """Verify rotated field polygons match raster rotated masks with IoU >= 0.95."""
    img, label = generate_single_sample(sample_template, seed=55, composite=True)
    label["base_image_id"] = "test_sample"

    for sev in (1, 2, 3):
        # Pick the first rectangular field with significant area
        test_field = next(
            f for f in label["fields"] if f["type"] in ("full_name", "pcn", "address")
        )
        poly = [tuple(pt) for pt in test_field["polygon"]]

        w, h = img.width, img.height
        mask = Image.new("L", (w, h), 0)
        draw = ImageDraw.Draw(mask)
        draw.polygon(poly, fill=255)

        rot_img, rot_label = apply_rotation(img, label, severity=sev, seed=10 + sev)
        rw, rh = rot_img.width, rot_img.height

        # Find the transformed field polygon in rot_label
        trans_field = next(f for f in rot_label["fields"] if f["name"] == test_field["name"])
        trans_poly = [tuple(pt) for pt in trans_field["polygon"]]

        # 1. Raster mask rotated via same parameters
        # Re-derive angle applied
        angles = {1: 6.0, 2: 15.0, 3: 32.0}
        base_angle = angles[sev]
        import random
        rng = random.Random((10 + sev) ^ 0x726F7461)
        sign = -1.0 if rng.random() < 0.5 else 1.0
        angle_deg = sign * (base_angle + rng.uniform(-1.0, 1.0))

        mask_rot = mask.rotate(angle_deg, expand=True, resample=Image.Resampling.BILINEAR)

        # 2. Transformed polygon rasterized on fresh canvas
        poly_mask = Image.new("L", (rw, rh), 0)
        draw2 = ImageDraw.Draw(poly_mask)
        draw2.polygon(trans_poly, fill=255)

        iou = compute_mask_iou(mask_rot, poly_mask)
        print(f"Rotation severity {sev} IoU: {iou:.4f}")
        assert iou >= 0.95, f"Rotation severity {sev} IoU {iou:.4f} below 0.95"


def test_perspective_polygons_match_transformed_mask_iou(sample_template: dict[str, Any]) -> None:
    """Verify perspective-warped field polygons match warped masks with IoU >= 0.95."""
    img, label = generate_single_sample(sample_template, seed=77, composite=True)
    label["base_image_id"] = "test_sample"

    for sev in (1, 2, 3):
        test_field = next(
            f for f in label["fields"] if f["type"] in ("full_name", "pcn", "address")
        )
        poly = [tuple(pt) for pt in test_field["polygon"]]

        w, h = img.width, img.height
        mask = Image.new("L", (w, h), 0)
        draw = ImageDraw.Draw(mask)
        draw.polygon(poly, fill=255)

        # Apply perspective condition
        warp_img, warp_label = apply_perspective(img, label, severity=sev, seed=20 + sev)

        trans_field = next(f for f in warp_label["fields"] if f["name"] == test_field["name"])
        trans_poly = [tuple(pt) for pt in trans_field["polygon"]]

        # Re-generate same PIL transform for mask
        import random
        rng = random.Random((20 + sev) ^ 0x70657273)
        ratios = {1: 0.05, 2: 0.10, 3: 0.18}
        ratio = ratios[sev]
        dx_val = int(w * ratio)
        dy_val = int(h * ratio)
        mode = rng.randint(0, 3)
        src_corners = [(0, 0), (w, 0), (w, h), (0, h)]
        if mode == 0:
            dst_corners = [(dx_val, dy_val), (w - dx_val, dy_val), (w, h), (0, h)]
        elif mode == 1:
            dst_corners = [(0, 0), (w, 0), (w - dx_val, h - dy_val), (dx_val, h - dy_val)]
        elif mode == 2:
            dst_corners = [(dx_val, dy_val), (w, 0), (w, h), (dx_val, h - dy_val)]
        else:
            dst_corners = [(0, 0), (w - dx_val, dy_val), (w - dx_val, h - dy_val), (0, h)]

        from ml.conditions import _solve_perspective_system
        pil_coeffs = _solve_perspective_system(src_corners, dst_corners)

        mask_warped = mask.transform(
            (w, h),
            Image.Transform.PERSPECTIVE,
            pil_coeffs,
            resample=Image.Resampling.BILINEAR,
        )

        poly_mask = Image.new("L", (w, h), 0)
        draw2 = ImageDraw.Draw(poly_mask)
        draw2.polygon(trans_poly, fill=255)

        iou = compute_mask_iou(mask_warped, poly_mask)
        print(f"Perspective severity {sev} IoU: {iou:.4f}")
        assert iou >= 0.95, f"Perspective severity {sev} IoU {iou:.4f} below 0.95"


def test_crop_polygons_match_transformed_mask_iou(sample_template: dict[str, Any]) -> None:
    """Verify cropped field polygons match cropped masks with IoU >= 0.95 across all fields."""
    img, label = generate_single_sample(sample_template, seed=99, composite=True)
    label["base_image_id"] = "test_sample"
    w, h = img.width, img.height

    for sev in (1, 2, 3):
        crop_img, crop_label = apply_crop(img, label, severity=sev, seed=30 + sev)
        cw, ch = crop_img.width, crop_img.height

        # Re-derive crop box
        import random
        rng = random.Random((30 + sev) ^ 0x63726F70)
        crop_fractions = {1: 0.05, 2: 0.12, 3: 0.22}
        frac = crop_fractions[sev]
        x1 = int(round(w * rng.uniform(frac * 0.6, frac * 1.2)))
        y1 = int(round(h * rng.uniform(frac * 0.6, frac * 1.2)))
        x2 = w - int(round(w * rng.uniform(frac * 0.6, frac * 1.2)))
        y2 = h - int(round(h * rng.uniform(frac * 0.6, frac * 1.2)))
        x1 = max(0, min(x1, w // 4))
        y1 = max(0, min(y1, h // 4))
        x2 = min(w, max(x2, 3 * w // 4))
        y2 = min(h, max(y2, 3 * h // 4))

        cropped_fields_by_name = {f["name"]: f for f in crop_label["fields"]}

        # Iterate over all fields in the ground truth
        for test_field in label["fields"]:
            poly = [tuple(pt) for pt in test_field["polygon"]]
            mask = Image.new("L", (w, h), 0)
            draw = ImageDraw.Draw(mask)
            draw.polygon(poly, fill=255)

            mask_cropped = mask.crop((x1, y1, x2, y2))
            crop_pixel_count = sum(1 for p in mask_cropped.getdata() if p > 127)

            if test_field["name"] not in cropped_fields_by_name:
                # Field had no visible area after crop and was dropped
                assert crop_pixel_count == 0, (
                    f"Field '{test_field['name']}' was dropped but has visible pixels in crop"
                )
                continue

            trans_field = cropped_fields_by_name[test_field["name"]]
            trans_poly = [tuple(pt) for pt in trans_field["polygon"]]

            poly_mask = Image.new("L", (cw, ch), 0)
            draw2 = ImageDraw.Draw(poly_mask)
            draw2.polygon(trans_poly, fill=255)

            iou = compute_mask_iou(mask_cropped, poly_mask)
            assert iou >= 0.95, (
                f"Crop severity {sev} field '{test_field['name']}' IoU {iou:.4f} below 0.95"
            )


def test_all_labelled_polygons_in_bounds_after_crop_all_templates() -> None:
    """Verify that every labelled polygon is within image bounds after crop for all templates."""
    templates = get_available_templates()
    assert len(templates) > 0

    for tmpl_id, tmpl in templates.items():
        for seed in (42, 100):
            img, label = generate_single_sample(tmpl, seed=seed, composite=True)
            for sev in (1, 2, 3):
                crop_img, crop_label = apply_crop(img, label, severity=sev, seed=seed)
                cw, ch = crop_img.size

                # At least one field should still be visible in partial crop
                assert len(crop_label["fields"]) > 0, (
                    f"Template {tmpl_id} sev {sev} has no fields left"
                )

                for field in crop_label["fields"]:
                    poly = field["polygon"]
                    assert len(poly) >= 3, (
                        f"Template {tmpl_id} field '{field['name']}' has < 3 vertices: {poly}"
                    )
                    for x, y in poly:
                        assert 0 <= x <= cw, (
                            f"Template {tmpl_id} sev {sev} field '{field['name']}' vertex x={x} "
                            f"out of [0, {cw}]"
                        )
                        assert 0 <= y <= ch, (
                            f"Template {tmpl_id} sev {sev} field '{field['name']}' vertex y={y} "
                            f"out of [0, {ch}]"
                        )


# ---------------------------------------------------------------------------
# Criterion 4: Label records condition name, severity, and base image id
# ---------------------------------------------------------------------------


def test_label_records_condition_severity_and_base_image_id(
    sample_template: dict[str, Any],
) -> None:
    """Verify label dictionary contains condition, severity, and base_image_id."""
    img, base_label = generate_single_sample(sample_template, seed=42)
    base_label["base_image_id"] = "philsys-card_0000"

    cond_img, cond_label = apply_condition(
        img,
        base_label,
        condition="blur",
        severity=2,
        seed=101,
    )

    assert cond_label["condition"] == "blur"
    assert cond_label["severity"] == 2
    assert cond_label["base_image_id"] == "philsys-card_0000"


# ---------------------------------------------------------------------------
# CLI --conditions and --jitter parsing and dataset integration
# ---------------------------------------------------------------------------


def test_parse_conditions_arg() -> None:
    """Verify CLI conditions argument parsing logic."""
    assert parse_conditions_arg(None) == []
    assert parse_conditions_arg("") == []

    # "all" expands to 8 conditions * 3 severities = 24 pairs
    all_pairs = parse_conditions_arg("all")
    assert len(all_pairs) == 24
    assert {c for c, s in all_pairs} == set(CONDITION_NAMES)

    # "all:1" or "all:mild"
    all_sev1 = parse_conditions_arg("all:mild")
    assert len(all_sev1) == 8
    assert all(s == 1 for c, s in all_sev1)

    # Specific pairs
    pairs = parse_conditions_arg("blur:1,perspective:2,crop:severe,low-resolution:3")
    assert pairs == [
        ("blur", 1),
        ("perspective", 2),
        ("crop", 3),
        ("low_resolution", 3),
    ]


def test_parse_conditions_arg_empty_severity_and_dedupe() -> None:
    """Verify empty severity tokens are rejected and duplicate condition pairs are deduped."""
    with pytest.raises(ValueError, match="Empty severity"):
        parse_conditions_arg("blur:")

    with pytest.raises(ValueError, match="Empty severity"):
        parse_conditions_arg("all:")

    with pytest.raises(ValueError, match="Empty severity"):
        parse_conditions_arg("perspective:2,blur:")

    # Deduplication preserves first occurrence order
    deduped = parse_conditions_arg("blur:1,blur:1,glare:2,blur:1")
    assert deduped == [("blur", 1), ("glare", 2)]

    # Deduplication when expanding 'all' or repeated condition names
    deduped_all = parse_conditions_arg("all:1,blur:1")
    assert len(deduped_all) == 8
    assert deduped_all.count(("blur", 1)) == 1


def test_jpeg_compression_rgba_input() -> None:
    """Verify JPEG compression converts RGBA input to RGB without raising an error."""
    rgba_img = Image.new("RGBA", (200, 200), (255, 100, 50, 128))
    label: dict[str, Any] = {"fields": []}
    out_img, out_label = apply_condition(rgba_img, label, condition="jpeg_compression", severity=1)
    assert out_img.mode == "RGB"
    assert out_img.size == (200, 200)


def test_generate_dataset_with_conditions_and_jitter(tmp_path: Path) -> None:
    """Verify generate_dataset produces base and condition files with metadata."""
    out_dir = tmp_path / "cond_out"
    files = generate_dataset(
        variant="philsys-card",
        count=1,
        seed=42,
        out_dir=out_dir,
        jitter=True,
        conditions="blur:1,glare:2",
    )

    # 1 base image + 2 condition images = 3 total samples
    assert len(files) == 3

    base_img_file, base_json_file = files[0]
    assert base_img_file.name == "philsys-card_0000.png"
    with open(base_json_file, encoding="utf-8") as f:
        base_data = json.load(f)
        assert base_data["base_image_id"] == "philsys-card_0000"
        assert base_data["condition"] is None
        assert base_data["severity"] is None
        assert base_data["layout_jitter"] is True

    blur_img_file, blur_json_file = files[1]
    assert blur_img_file.name == "philsys-card_0000_blur_s1.png"
    with open(blur_json_file, encoding="utf-8") as f:
        blur_data = json.load(f)
        assert blur_data["base_image_id"] == "philsys-card_0000"
        assert blur_data["condition"] == "blur"
        assert blur_data["severity"] == 1
        assert blur_data["image_path"] == "philsys-card_0000_blur_s1.png"

def test_cli_subprocess_conditions_and_jitter(tmp_path: Path) -> None:
    """Verify CLI generation with --conditions and --jitter runs deterministically."""
    import subprocess
    import sys

    out_dir = tmp_path / "cli_cond_out"
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
        "--jitter",
        "--conditions",
        "blur:1,glare:2",
        "--out",
        str(out_dir),
    ]

    res = subprocess.run(cmd, capture_output=True, text=True, check=True)
    assert "Generated 3 synthetic card samples" in res.stdout

    base_json = out_dir / "philsys-card_0000.json"
    cond_json = out_dir / "philsys-card_0000_blur_s1.json"

    assert base_json.is_file()
    assert cond_json.is_file()

    with open(cond_json, encoding="utf-8") as f:
        data = json.load(f)
        assert data["condition"] == "blur"
        assert data["severity"] == 1
        assert data["base_image_id"] == "philsys-card_0000"
        assert data["layout_jitter"] is True

