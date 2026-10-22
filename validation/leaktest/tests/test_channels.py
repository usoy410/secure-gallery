"""Tests for the partial-character, non-opaque-fill and mosaic/blur channels (issue #12).

Each channel has a positive fixture (it must flag) and a negative one (it must not).
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path

import pytest
from PIL import Image, ImageDraw, ImageFilter

from ml.generate import generate_single_sample, get_available_templates
from validation.leaktest.channels import (
    as_polygon,
    band_crop,
    boxes_over_field,
    fill_style,
    mosaic_blur,
)
from validation.leaktest.core import (
    FieldResult,
    judge_field,
    load_boxes,
    summarize,
)

TEMPLATES = get_available_templates()

_has_paddle = importlib.util.find_spec("paddleocr") is not None
needs_paddle = pytest.mark.skipif(
    not _has_paddle and not os.environ.get("CI"), reason="PaddleOCR not installed"
)


def _jpeg(img: Image.Image) -> Image.Image:
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=95)
    buf.seek(0)
    return Image.open(buf).convert("RGB")


def _pcn_card(seed: int = 4) -> tuple[Image.Image, dict, tuple[int, int, int, int]]:
    """A clean PhilSys card, its PCN field and the PCN field's bounding box."""
    img, label = generate_single_sample(TEMPLATES["philsys-card"], seed=seed, composite=False)
    field = next(f for f in label["fields"] if f["type"] == "pcn")
    xs = [p[0] for p in field["polygon"]]
    ys = [p[1] for p in field["polygon"]]
    return img.convert("RGB"), field, (min(xs), min(ys), max(xs), max(ys))


def _ink_rows(img: Image.Image, box: tuple[int, int, int, int]) -> tuple[int, int]:
    """First and last image rows holding dark text pixels inside ``box``."""
    crop = img.crop(box).convert("L")
    rows = [
        y for y in range(crop.height) if min(crop.crop((0, y, crop.width, y + 1)).getdata()) < 100
    ]
    return box[1] + rows[0], box[1] + rows[-1]


def _styled(img: Image.Image, box: tuple[int, int, int, int], style: str) -> Image.Image:
    out = img.copy()
    crop = out.crop(box)
    if style == "solid":
        crop = Image.new("RGB", crop.size, (0, 0, 0))
    elif style == "mosaic":
        crop = crop.reduce(8).resize(crop.size, Image.Resampling.NEAREST)
    elif style == "blur":
        crop = crop.filter(ImageFilter.GaussianBlur(4))
    elif style == "dark_glass":  # looks black, but the text survives underneath
        glass = Image.new("RGBA", crop.size, (0, 0, 0, 232))
        crop = Image.alpha_composite(crop.convert("RGBA"), glass).convert("RGB")
    out.paste(crop, box[:2])
    return out


# --- geometry helpers ---------------------------------------------------------------


def test_box_formats_and_overlap_filter() -> None:
    assert as_polygon([1, 2, 3, 4]) == [(1, 2), (3, 2), (3, 4), (1, 4)]
    field = [[10, 10], [50, 10], [50, 30], [10, 30]]
    over = as_polygon([20, 12, 40, 28])
    elsewhere = as_polygon([60, 60, 80, 80])
    sliver = as_polygon([49, 10, 51, 30])  # 2.5% of the field: below the overlap floor
    assert boxes_over_field((100, 100), field, [over, elsewhere, sliver]) == [over]


def test_band_excludes_the_box() -> None:
    img = Image.new("RGB", (200, 100), (0, 0, 0))
    band = band_crop(img, [as_polygon([50, 40, 150, 60])])
    assert band is not None
    # 15% of the box's width and height on every side (+1 px: polygon edges are inclusive)
    assert band.size[0] in (130, 131) and band.size[1] in (26, 27)
    assert band.getpixel((65, 13)) == (255, 255, 255)  # the box itself is masked out
    assert band.getpixel((2, 1)) == (0, 0, 0)


def test_box_sources_in_priority_order(tmp_path: Path) -> None:
    image = tmp_path / "card_0001.jpg"
    label = {"redaction": {"redaction_boxes": [[1, 2, 3, 4]]}}
    assert load_boxes(image, {}) == (None, "field")
    assert load_boxes(image, label) == ([[1, 2, 3, 4]], "label")
    polygon = [[0, 0], [5, 0], [5, 5]]
    record = {"boxes": [{"field_type": "pcn", "source": "layout", "polygon": polygon}]}
    (tmp_path / "card_0001.boxes.json").write_text(json.dumps(record), encoding="utf-8")
    assert load_boxes(image, label) == ([[[0, 0], [5, 0], [5, 5]]], "file")


# --- mosaic / blur ------------------------------------------------------------------------


@pytest.mark.parametrize("seed", [1, 2, 3])
@pytest.mark.parametrize("compress", [False, True])
def test_fill_style_classification(seed: int, compress: bool) -> None:
    img, _, box = _pcn_card(seed)
    for style, expected in (("solid", "solid"), ("mosaic", "mosaic"), ("blur", "blur"),
                            ("none", "other")):  # fmt: skip
        out = _styled(img, box, style)
        crop = (_jpeg(out) if compress else out).crop(box)
        assert fill_style(crop)[0] == expected, (style, compress)


def test_mosaic_or_blur_over_text_is_a_risk_but_solid_is_not() -> None:
    img, _, box = _pcn_card()
    poly = [as_polygon(list(box))]
    for style, risky in (("mosaic", True), ("blur", True), ("solid", False)):
        outcome = mosaic_blur(_styled(img, box, style), poly)
        assert outcome.leaked is risky, style
        assert outcome.rule == style


def test_mosaic_risk_is_not_counted_as_a_recovered_leak() -> None:
    def row(channel: str, leaked: bool) -> FieldResult:
        return FieldResult("img", "philsys", "philsys-card", "none", "pcn", "PCN", "label",
                           channel, True, leaked, 0.0, "x")  # fmt: skip

    summary = summarize([row("visible_pixels", False), row("opacity", False),
                         row("mosaic_blur", True)])  # fmt: skip
    rows = {(r["document_class"], r["condition"], r["channel"]): r for r in summary}
    assert rows[("philsys", "none", "mosaic_blur")]["leaked"] == 1
    assert rows[("philsys", "none", "any")]["leaked"] == 0


# --- OCR channels (PaddleOCR) -----------------------------------------------------------


@needs_paddle
def test_partial_characters_above_a_low_box_are_detected() -> None:
    from validation.leaktest.ocr import paddle_read

    img, field, box = _pcn_card()
    top, bottom = _ink_rows(img, box)
    # A tall box whose top edge cuts 60% down the digits: the band above it (15% of the
    # box height) still shows their upper part.
    cut = top + round((bottom - top) * 0.6)
    low_box = [box[0], cut, box[2], cut + 80]
    redacted = img.copy()
    ImageDraw.Draw(redacted).rectangle(low_box, fill=(0, 0, 0))
    verdict = judge_field(redacted, field, paddle_read, boxes=[low_box])["partial_chars"]
    assert verdict[:2] == (True, True), verdict

    # A box that covers the whole field with a margin leaves nothing in the band.
    full_box = [box[0] - 4, box[1] - 4, box[2] + 4, box[3] + 4]
    redacted = img.copy()
    ImageDraw.Draw(redacted).rectangle(full_box, fill=(0, 0, 0))
    verdict = judge_field(redacted, field, paddle_read, boxes=[full_box])["partial_chars"]
    assert verdict[:2] == (True, False), verdict


@needs_paddle
def test_dark_see_through_fill_is_detected_and_solid_fill_is_not() -> None:
    from validation.leaktest.ocr import paddle_read

    img, field, box = _pcn_card()
    for style, leaks in (("dark_glass", True), ("solid", False)):
        redacted = _jpeg(_styled(img, box, style))
        verdict = judge_field(redacted, field, paddle_read, boxes=[list(box)])["non_opaque_fill"]
        assert verdict[:2] == (True, leaks), (style, verdict)
