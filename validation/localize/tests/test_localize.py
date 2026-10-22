"""Tests for the desktop localization strategies (issue #10)."""

from __future__ import annotations

import json
import math
import re
import shutil
from pathlib import Path
from typing import Any

import pytest
from PIL import Image, ImageChops, ImageDraw, ImageFilter

from ml.data_generator import SyntheticDataGenerator
from ml.generate import generate_single_sample
from validation.localize.__main__ import main, run
from validation.localize.core import (
    JPEG_QUALITY,
    PICTURE_FIELDS,
    STRATEGIES,
    Box,
    complete_corners,
    load_rules,
    load_templates,
    localize,
    redact,
    save_jpeg,
)

pytestmark = pytest.mark.skipif(shutil.which("tesseract") is None, reason="needs tesseract")

TEMPLATES = load_templates()
RULES, EXCLUDED = load_rules()


def _card(variant: str = "philsys-card", seed: int = 3) -> tuple[Image.Image, dict[str, Any]]:
    img, label = generate_single_sample(TEMPLATES[variant], seed=seed, composite=False)
    return img.convert("RGB"), label


def _rotate(img: Image.Image, label: dict[str, Any], angle: float) -> tuple[Image.Image, dict]:
    """Rotate counter-clockwise on a grey canvas and move every label point with it."""
    out = img.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC, fillcolor=(90, 90, 90))
    a = math.radians(angle)
    cx, cy = img.width / 2, img.height / 2
    nx, ny = out.width / 2, out.height / 2

    def move(p: list[float]) -> list[float]:
        dx, dy = p[0] - cx, p[1] - cy
        return [math.cos(a) * dx + math.sin(a) * dy + nx, -math.sin(a) * dx + math.cos(a) * dy + ny]

    new = json.loads(json.dumps(label))
    new["card_corners"] = [move(p) for p in label["card_corners"]]
    for field in new["fields"]:
        field["polygon"] = [move(p) for p in field["polygon"]]
    return out, new


def _run(img: Image.Image, label: dict[str, Any], strategy: str, corners: Any = "label"):
    return localize(
        img,
        strategy,
        is_document=True,
        template=TEMPLATES[label["variant"]],
        raw_corners=label["card_corners"] if corners == "label" else corners,
        rules=RULES,
        excluded=EXCLUDED,
    )


def _mask(polygons: list, size: tuple[int, int]) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    for poly in polygons:
        draw.polygon([tuple(p) for p in poly], fill=255)
    return mask


def _area(mask: Image.Image) -> int:
    return mask.histogram()[255]


def _coverage(field_polygon: list, boxes: tuple[Box, ...], size: tuple[int, int]) -> float:
    """Share of the ground-truth field polygon covered by the redaction boxes."""
    truth = _mask([field_polygon], size)
    covered = ImageChops.multiply(truth, _mask([b.polygon for b in boxes], size))
    return _area(covered) / max(_area(truth), 1)


def _field(label: dict[str, Any], kind: str) -> dict[str, Any]:
    return next(f for f in label["fields"] if f["type"] == kind)


def _inside(point: tuple[float, float], polygon: list, size: tuple[int, int]) -> bool:
    x, y = round(point[0]), round(point[1])
    return 0 <= x < size[0] and 0 <= y < size[1] and _mask([polygon], size).getpixel((x, y)) == 255


def _center(box: Box) -> tuple[float, float]:
    xs, ys = zip(*box.polygon, strict=True)
    return sum(xs) / len(xs), sum(ys) / len(ys)


# --- rules -------------------------------------------------------------------


def test_rules_load_and_name_known_field_types() -> None:
    schema = json.loads((Path("templates") / "schema.json").read_text(encoding="utf-8"))
    known = set(schema["definitions"]["field"]["properties"]["type"]["enum"])
    assert RULES
    assert {r.field_type for r in RULES} <= known


@pytest.mark.parametrize("seed", range(40))
def test_rules_match_every_generated_text_value(seed: int) -> None:
    values = SyntheticDataGenerator(seed=seed).generate_fields_for_template(
        TEMPLATES["lto-dl-current"]
    )
    expect = {
        "pcn": "pcn",
        "license_number": "license_number",
        "card_number": "card_number",
        "date_of_birth": "date_of_birth",
        "expiry_date": "expiry_date",
        "cvv": "cvv",
        "full_name": "full_name",
        "name": "full_name",  # OCR-first cannot tell an LTO name from a PhilSys one
        "cardholder_name": "cardholder_name",
        "address": "address",
    }
    for field_type, rule_type in expect.items():
        line = values[field_type].split("\n")[0].upper()
        rules = [r for r in RULES if r.field_type == rule_type]
        assert any(r.pattern.search(line) for r in rules), (field_type, line)


def test_card_number_rule_requires_luhn() -> None:
    rule = next(r for r in RULES if r.field_type == "card_number")
    assert rule.luhn
    assert rule.pattern.search("4111 1111 1111 1111")
    pcn = next(r for r in RULES if r.field_type == "pcn")
    assert not pcn.pattern.search("4111 1111 1111 1111")
    assert re.fullmatch(pcn.pattern, "1234-5678-9012-3456")


# --- corners -------------------------------------------------------------------


def test_one_missing_corner_is_inferred_as_parallelogram() -> None:
    full = [[10, 20], [110, 30], [100, 90], [0, 80]]
    for i in range(4):
        raw = [None if k == i else p for k, p in enumerate(full)]
        got = complete_corners(raw)
        assert got is not None
        assert got[i] == pytest.approx(tuple(full[i]))


def test_two_missing_corners_or_bad_shape_give_none() -> None:
    assert complete_corners([[0, 0], None, None, [0, 5]]) is None
    assert complete_corners([]) is None
    assert complete_corners(None) is None
    assert complete_corners([[0, 0], [1, 0], [1, 1]]) is None


# --- hand-made cases -------------------------------------------------------------


def test_layout_only_covers_every_sensitive_field_on_a_rotated_card() -> None:
    img, label = _rotate(*_card(), 25)
    result = _run(img, label, "layout_only")
    assert not result.fallback
    for field in label["fields"]:
        if field["sensitive"]:
            assert _coverage(field["polygon"], result.boxes, img.size) > 0.97, field["type"]


@pytest.mark.parametrize("variant", ["philsys-card", "lto-dl-current"])
def test_picture_boxes_still_cover_fields_moved_by_the_largest_jitter(variant: str) -> None:
    img, label = _card(variant)
    xs = [p[0] for p in label["card_corners"]]
    ys = [p[1] for p in label["card_corners"]]
    width, height = max(xs) - min(xs), max(ys) - min(ys)
    # ml/jitter.py: field offset up to 2% of the card width, print offset up to 0.8%
    # of each dimension, in both directions.
    dx, dy = 0.02 * width + 0.008 * width, 0.02 * width + 0.008 * height
    result = _run(img, label, "layout_only")
    pictures = [f for f in label["fields"] if f["type"] in PICTURE_FIELDS]
    assert pictures
    for field in pictures:
        for sx, sy in ((dx, dy), (-dx, dy), (dx, -dy), (-dx, -dy)):
            moved = [[x + sx, y + sy] for x, y in field["polygon"]]
            assert _coverage(moved, result.boxes, img.size) > 0.995, (field["type"], sx, sy)
    # Text fields keep their template size.
    pcn = next(b for b in result.boxes if b.field_type in ("pcn", "license_number"))
    assert _coverage(_field(label, pcn.field_type)["polygon"], (pcn,), img.size) > 0.97


def test_ocr_first_reads_a_rotated_card_without_template_knowledge() -> None:
    img, label = _rotate(*_card(), 25)
    with_template = _run(img, label, "ocr_first")
    without = localize(
        img, "ocr_first", is_document=True, template=None,
        raw_corners=label["card_corners"], rules=RULES, excluded=EXCLUDED,
    )  # fmt: skip
    assert with_template.boxes == without.boxes
    found = {b.field_type for b in without.boxes}
    assert {"pcn", "date_of_birth"} <= found
    pcn = _field(label, "pcn")["polygon"]
    for box in without.boxes:
        if box.field_type == "pcn":
            assert _inside(_center(box), pcn, img.size)


def test_blurred_field_is_missed_by_ocr_but_kept_by_layout_and_hybrid() -> None:
    img, label = _card()
    pcn = _field(label, "pcn")["polygon"]
    xs, ys = [p[0] for p in pcn], [p[1] for p in pcn]
    region = (min(xs), min(ys), max(xs), max(ys))
    img.paste(img.crop(region).filter(ImageFilter.GaussianBlur(12)), region[:2])

    assert "pcn" not in {b.field_type for b in _run(img, label, "ocr_first").boxes}
    for strategy in ("layout_only", "hybrid"):
        assert _coverage(pcn, _run(img, label, strategy).boxes, img.size) > 0.97, strategy


def test_hybrid_is_the_union_of_both_strategies() -> None:
    img, label = _rotate(*_card("lto-dl-current", 8), -12)
    layout = _run(img, label, "layout_only").boxes
    ocr = _run(img, label, "ocr_first").boxes
    hybrid = _run(img, label, "hybrid").boxes
    assert set(hybrid) == set(layout) | set(ocr)
    assert len(hybrid) == len(layout) + len(ocr)


def test_one_missing_corner_still_localizes() -> None:
    img, label = _rotate(*_card(), 15)
    corners = list(label["card_corners"])
    corners[2] = None
    result = _run(img, label, "layout_only", corners=corners)
    assert not result.fallback
    for field in label["fields"]:
        if field["sensitive"]:
            assert _coverage(field["polygon"], result.boxes, img.size) > 0.95, field["type"]


def test_too_few_corners_fall_back_unless_ocr_finds_a_field() -> None:
    img, label = _card()
    corners = [label["card_corners"][0], None, None, label["card_corners"][3]]

    layout = _run(img, label, "layout_only", corners=corners)
    assert layout.fallback
    assert [b.source for b in layout.boxes] == ["fallback"]
    assert _coverage([[0, 0], [img.width, 0], [img.width, img.height], [0, img.height]],
                     layout.boxes, img.size) == 1.0  # fmt: skip

    # The card is upright, so OCR on the whole image still finds fields: no fallback.
    for strategy in ("ocr_first", "hybrid"):
        result = _run(img, label, strategy, corners=corners)
        assert not result.fallback
        assert "pcn" in {b.field_type for b in result.boxes}


def test_whole_document_fallback_when_no_corners_and_no_text() -> None:
    blank = Image.new("RGB", (800, 500), (200, 200, 200))
    for strategy in STRATEGIES:
        result = localize(blank, strategy, is_document=True, template=TEMPLATES["philsys-card"],
                          raw_corners=None, rules=RULES, excluded=EXCLUDED)  # fmt: skip
        assert result.fallback
        assert [b.source for b in result.boxes] == ["fallback"]


def test_negatives_never_fall_back() -> None:
    blank = Image.new("RGB", (800, 500), (200, 200, 200))
    for strategy in STRATEGIES:
        result = localize(blank, strategy, is_document=False, template=None, raw_corners=[],
                          rules=RULES, excluded=EXCLUDED)  # fmt: skip
        assert not result.fallback
        assert result.boxes == ()


# --- redaction output -------------------------------------------------------------


def test_redaction_is_a_solid_fill_saved_as_jpeg_q95(tmp_path: Path) -> None:
    img, label = _card()
    result = _run(img, label, "layout_only")
    path = tmp_path / "out.jpg"
    save_jpeg(redact(img, result.boxes), path)

    reference = tmp_path / "ref.jpg"
    img.save(reference, format="JPEG", quality=JPEG_QUALITY)
    with Image.open(path) as out, Image.open(reference) as ref:
        assert out.format == "JPEG"
        assert out.quantization == ref.quantization
        pcn = _field(label, "pcn")["polygon"]
        xs, ys = [p[0] for p in pcn], [p[1] for p in pcn]
        inner = out.convert("L").crop((min(xs) + 6, min(ys) + 6, max(xs) - 6, max(ys) - 6))
        assert inner.getextrema()[1] <= 8  # opaque black, no text showing through


# --- CLI -------------------------------------------------------------------------


def _dataset(root: Path) -> Path:
    from ml.generate import generate_dataset

    data = root / "data"
    generate_dataset("philsys-card", 1, 11, data, composite=True)
    generate_dataset("payment-card-front", 1, 11, data, composite=True)
    return data


def test_cli_writes_redacted_images_and_boxes_without_text(tmp_path: Path) -> None:
    data = _dataset(tmp_path)
    for strategy in STRATEGIES:
        main([str(data), "--strategy", strategy, "--out", str(tmp_path / "out")])
        boxes = sorted((tmp_path / "out" / strategy).glob("*.boxes.json"))
        assert len(boxes) == 2
        for path in boxes:
            record = json.loads(path.read_text(encoding="utf-8"))
            assert record["strategy"] == strategy
            assert (path.parent / Path(record["image"]).name).exists()
            for box in record["boxes"]:
                assert set(box) == {"field_type", "source", "polygon"}


def test_cli_model_corners_mode(tmp_path: Path) -> None:
    data = _dataset(tmp_path)
    label_path = sorted(data.glob("philsys-card_*.json"))[0]
    label = json.loads(label_path.read_text(encoding="utf-8"))
    model = tmp_path / "model"
    model.mkdir()
    detected = {"variant": label["variant"], "card_corners": label["card_corners"]}
    (model / label_path.name).write_text(json.dumps(detected), encoding="utf-8")

    written = run(data, "layout_only", tmp_path / "out", corners="model", model_dir=model)
    records = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in written}
    detected_record = records[label_path.stem + ".boxes.json"]
    assert detected_record["is_document"] and detected_record["boxes"]
    # No detector output for the other image: treated as no document, so no boxes.
    others = [r for name, r in records.items() if name != label_path.stem + ".boxes.json"]
    assert others and all(not r["is_document"] and not r["boxes"] for r in others)


def test_cli_missing_image_is_an_error(tmp_path: Path) -> None:
    data = _dataset(tmp_path)
    next(data.glob("*.png")).unlink()
    with pytest.raises(FileNotFoundError):
        run(data, "layout_only", tmp_path / "out")
