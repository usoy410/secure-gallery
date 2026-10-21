"""Tests for the synthetic card generator (issue #5)."""

from __future__ import annotations

import json
import os
import random
import re
import shutil
import unicodedata
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from ml.generate import render
from ml.generate.__main__ import main
from ml.generate.compose import BACKGROUNDS, _warp, apply
from ml.generate.render import card_size, field_box, render_card
from ml.generate.sample import generate_sample, image_seed, load_templates, write_dataset
from ml.generate.values import IMAGE_FIELDS, TEST_CARD_NUMBERS, luhn_valid, make_values

TEMPLATES = load_templates()


def test_templates_found() -> None:
    assert len(TEMPLATES) >= 6


@pytest.mark.parametrize("variant", sorted(TEMPLATES))
def test_every_variant_renders_with_complete_label(variant: str) -> None:
    template = TEMPLATES[variant]
    scene, card, label = generate_sample(template, image_seed(0, variant, 0))

    assert card.size == card_size(template)
    assert (label["width"], label["height"]) == scene.size
    assert label["synthetic"] is True
    assert label["document_class"] == template["document_class"]
    assert label["variant"] == variant
    assert label["background"] in BACKGROUNDS
    assert len(label["card_corners"]) == 4
    assert [f["type"] for f in label["fields"]] == [f["type"] for f in template["fields"]]
    for field in label["fields"]:
        for x, y in field["polygon"] + label["card_corners"]:
            assert 0 <= x <= scene.width and 0 <= y <= scene.height
        if field["type"] in IMAGE_FIELDS:
            assert field["value"] is None
        else:
            assert isinstance(field["value"], str) and field["value"].strip()


def test_value_formats() -> None:
    for seed in range(200):
        philsys = make_values("philsys", seed)
        assert re.fullmatch(r"[1-9]\d{3}-\d{4}-\d{4}-\d{4}", philsys["pcn"])
        assert re.fullmatch(r"[A-Z]+ \d{2}, (19[5-9]\d|200[0-6])", philsys["date_of_birth"])
        assert len(philsys["full_name"].split("\n")) == 3

        lto = make_values("lto", seed)
        assert re.fullmatch(r"[A-Z]\d{2}-\d{2}-\d{6}", lto["license_number"])
        assert re.fullmatch(r"\d{4}/\d{2}/\d{2}", lto["date_of_birth"])
        assert lto["sex"] in {"M", "F"}

        card = make_values("payment_card", seed)
        assert re.fullmatch(r"(0[1-9]|1[0-2])/\d{2}", card["expiry_date"])
        assert re.fullmatch(r"\d{3}", card["cvv"])


def test_card_numbers_are_published_test_numbers_and_pass_luhn() -> None:
    for number in TEST_CARD_NUMBERS:
        assert luhn_valid(number), number
    for seed in range(200):
        pan = make_values("payment_card", seed)["card_number"].replace(" ", "")
        assert pan in TEST_CARD_NUMBERS
        assert luhn_valid(pan)


def test_luhn_rejects_bad_numbers() -> None:
    assert not luhn_valid("4111111111111112")
    assert not luhn_valid("4111-1111-1111-1111")
    assert not luhn_valid("")


@pytest.mark.parametrize("variant", sorted(TEMPLATES))
def test_every_card_carries_the_specimen_watermark(variant: str, monkeypatch) -> None:
    template = TEMPLATES[variant]
    values = make_values(template["document_class"], 1)
    marked = np.asarray(render_card(template, values, random.Random(5)))
    monkeypatch.setattr(render, "watermark", lambda img: img.convert("RGBA"))
    plain = np.asarray(render_card(template, values, random.Random(5)))

    changed = np.any(marked[..., :3] != plain[..., :3], axis=-1)
    h, w = changed.shape
    assert changed.mean() > 0.02
    # The mark rises from bottom-left to top-right through the center.
    for fx in (0.3, 0.5, 0.7):
        cx, cy = round(w * fx), round(h * (1 - fx))
        assert changed[cy - 40 : cy + 40, cx - 40 : cx + 40].any()


def _affine_from_corners(corners: list[list[float]], size: tuple[int, int]) -> np.ndarray:
    w, h = size
    src = np.array([[0, 0, 1], [w, 0, 1], [w, h, 1]], dtype=float)
    dst = np.array(corners[:3], dtype=float)
    m = np.linalg.solve(src, dst).T
    return np.vstack([m, [0, 0, 1]])


def test_label_polygons_locate_the_card_in_the_final_image() -> None:
    seen = set()
    for index in range(12):
        template = TEMPLATES[sorted(TEMPLATES)[index % len(TEMPLATES)]]
        scene, card, label = generate_sample(template, image_seed(7, template["id"], index))
        seen.add(label["background"])
        m = _affine_from_corners(label["card_corners"], card.size)

        # The fourth corner and every field polygon follow from the same transform.
        w, h = card.size
        np.testing.assert_allclose(apply(m, [(0, h)])[0], label["card_corners"][3], atol=0.05)
        for field, lf in zip(template["fields"], label["fields"], strict=True):
            expected = apply(m, [(x * w, y * h) for x, y in field["polygon"]])
            np.testing.assert_allclose(expected, lf["polygon"], atol=0.05)

        # Re-projecting the flat card with the labeled corners reproduces the scene.
        projected = np.asarray(_warp(card, m, scene.size), dtype=np.float32)
        inside = projected[..., 3] == 255
        diff = np.abs(projected[..., :3] - np.asarray(scene, dtype=np.float32))[inside]
        assert inside.sum() > 1000
        assert diff.mean() < 2.0
    assert seen == set(BACKGROUNDS)


def test_same_seed_gives_byte_identical_output(tmp_path: Path) -> None:
    a = write_dataset("all", 1, 42, tmp_path / "a")
    b = write_dataset("all", 1, 42, tmp_path / "b")
    c = write_dataset("philsys-card", 1, 43, tmp_path / "c")
    assert len(a) == len(TEMPLATES)
    for pa, pb in zip(a, b, strict=True):
        assert pa.read_bytes() == pb.read_bytes()
        ja, jb = pa.with_suffix(".json"), pb.with_suffix(".json")
        assert ja.read_bytes() == jb.read_bytes()
    first = next(p for p in a if p.name.startswith("philsys-card-"))
    assert c[0].read_bytes() != first.read_bytes()


def test_cli_writes_images_and_labels(tmp_path: Path) -> None:
    main(["--variant", "lto-dl-current", "--count", "2", "--seed", "1", "--out", str(tmp_path)])
    folder = tmp_path / "lto-dl-current"
    assert sorted(p.suffix for p in folder.iterdir()) == [".json", ".json", ".png", ".png"]
    label = json.loads((folder / "lto-dl-current-s1-00000.json").read_text(encoding="utf-8"))
    assert label["image"] == "lto-dl-current-s1-00000.png"
    with Image.open(folder / label["image"]) as img:
        assert img.size == (label["width"], label["height"])


def test_cli_rejects_unknown_variant(tmp_path: Path) -> None:
    with pytest.raises(SystemExit):
        main(["--variant", "nope", "--out", str(tmp_path)])


# OCR check: render, crop each text field's polygon from the clean flat card, OCR it,
# and compare with the label value. CI installs tesseract; locally the test skips
# without it.
_has_tesseract = shutil.which("tesseract") is not None


def _ocr_key(text: str) -> str:
    """Compare alphanumeric content only. Tesseract adds spaces around narrow glyphs
    and cannot tell O/0 and I/1 apart in this sans font; neither changes which text
    sits in the polygon."""
    text = unicodedata.normalize("NFKD", text.upper())
    return re.sub(r"[^A-Z0-9]", "", text).translate(str.maketrans("OI", "01"))


def _ocr_field(card: Image.Image, box: tuple[int, int, int, int], multiline: bool) -> str:
    import pytesseract

    gray = np.asarray(card.crop(box).convert("L"))
    if gray.mean() < 128:  # light text on a dark payment card
        gray = 255 - gray
    # Thresholding drops the light watermark and keeps the near-black text.
    binary = Image.fromarray(np.where(gray < 128, 0, 255).astype(np.uint8))
    padded = Image.new("L", (binary.width + 40, binary.height + 40), 255)
    padded.paste(binary, (20, 20))
    return pytesseract.image_to_string(padded, config=f"--psm {6 if multiline else 7}")


@pytest.mark.skipif(
    not _has_tesseract and not os.environ.get("CI"), reason="tesseract not installed"
)
def test_label_polygons_match_rendered_text_by_ocr() -> None:
    variants = sorted(TEMPLATES)
    matched = total = 0
    for index in range(50):
        template = TEMPLATES[variants[index % len(variants)]]
        _, card, label = generate_sample(template, image_seed(99, template["id"], index))
        flat = Image.new("RGB", card.size, "white")
        flat.paste(card, mask=card.getchannel("A"))
        for field, lf in zip(template["fields"], label["fields"], strict=True):
            if lf["value"] is None:
                continue
            multiline = "\n" in lf["value"] or field["type"] == "address"
            text = _ocr_field(flat, field_box(field, card.size), multiline)
            total += 1
            matched += _ocr_key(text) == _ocr_key(lf["value"])
    assert total >= 200
    assert matched / total >= 0.95, f"{matched}/{total} fields matched"
