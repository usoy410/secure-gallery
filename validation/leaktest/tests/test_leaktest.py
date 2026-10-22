"""Tests for the leak-test core (issue #11)."""

from __future__ import annotations

import csv
import importlib.util
import json
import os
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from ml.failed_redactions import FAILED_REDACTION_TYPES, apply_failed_redaction
from ml.generate import generate_dataset, generate_single_sample, get_available_templates
from validation.leaktest.__main__ import main, run
from validation.leaktest.core import (
    CHANNELS,
    OPACITY_MIN_SHARE,
    cer,
    digit_run_recovered,
    judge_field,
    judge_image,
    match_labels,
    summarize,
    uniform_share,
)

TEMPLATES = get_available_templates()
SQUARE = [[20, 20], [180, 20], [180, 80], [20, 80]]

_has_paddle = importlib.util.find_spec("paddleocr") is not None
# CI installs PaddleOCR, so a missing install there is a failure, not a skip.
needs_paddle = pytest.mark.skipif(
    not _has_paddle and not os.environ.get("CI"), reason="PaddleOCR not installed"
)


def _reader(text: str):  # noqa: ANN202
    return lambda crop: text


# --- text measures ----------------------------------------------------------------


def test_cer() -> None:
    assert cer("1234-5678", "1234-5678") == 0
    assert cer("1234 5678", "1234-5678") == pytest.approx(1 / 9)
    assert cer("", "ABC") == 1
    assert cer("dela  cruz\njuan", "DELA CRUZ JUAN") == 0  # case and whitespace ignored
    # Text around the value (here the SPECIMEN watermark) never hides a leak.
    assert cer("STEPHENS, JOHN, WOOD NOT VALIL", "STEPHENS, JOHN, WOOD") == 0
    assert cer("SPECIMEN 1234-5678", "1234-5678") == 0


def test_four_digit_rule() -> None:
    assert digit_run_recovered("xx 8737 yy", "9117-8364-8737-7219")
    assert digit_run_recovered("4242 4242", "4242 4242 4242 4242")
    assert digit_run_recovered("37-72", "9117-8364-8737-7219")  # dash inside a run is joined
    assert not digit_run_recovered("911 836", "9117-8364-8737-7219")
    assert not digit_run_recovered("1234", "123")  # value shorter than the run


# --- opacity -----------------------------------------------------------------------


def _canvas(color=(255, 255, 255)) -> Image.Image:
    return Image.new("RGB", (200, 100), color)


def test_solid_fill_is_opaque_even_with_jpeg_noise() -> None:
    img = _canvas()
    ImageDraw.Draw(img).rectangle((20, 20, 180, 80), fill=(0, 0, 0))
    assert uniform_share(img, SQUARE) == 1.0
    noisy = img.copy()
    for x in range(30, 170, 7):
        noisy.putpixel((x, 50), (6, 5, 7))  # within the tolerance of 8
    assert uniform_share(noisy, SQUARE) == 1.0


def test_half_cover_and_semi_transparent_fill_fail() -> None:
    half = _canvas()
    ImageDraw.Draw(half).text((30, 40), "SECRET 1234", fill=(0, 0, 0))
    ImageDraw.Draw(half).rectangle((20, 20, 100, 80), fill=(0, 0, 0))
    assert uniform_share(half, SQUARE) < OPACITY_MIN_SHARE

    see_through = _canvas().convert("RGBA")
    ImageDraw.Draw(see_through).text((30, 40), "SECRET 1234", fill=(0, 0, 0, 255))
    overlay = Image.new("RGBA", see_through.size, (0, 0, 0, 0))
    ImageDraw.Draw(overlay).rectangle((20, 20, 180, 80), fill=(0, 0, 0, 90))
    see_through = Image.alpha_composite(see_through, overlay).convert("RGB")
    assert uniform_share(see_through, SQUARE) < OPACITY_MIN_SHARE


def test_thin_seam_at_the_border_is_ignored() -> None:
    img = _canvas()
    ImageDraw.Draw(img).rectangle((21, 21, 179, 79), fill=(0, 0, 0))  # 1 px short on each side
    assert uniform_share(img, SQUARE) == 1.0


# --- judging -----------------------------------------------------------------------


def _field(kind: str, value: str = "9117-8364-8737-7219") -> dict:
    return {"type": kind, "name": kind, "sensitive": True, "polygon": SQUARE, "value": value}


def test_visible_channel_rules() -> None:
    img = _canvas()

    def visible(field: dict, text: str) -> tuple:
        return judge_field(img, field, _reader(text))["visible_pixels"]

    assert visible(_field("pcn"), "9117-8364-8737-7219") == (True, True, 0.0, "cer")
    assert visible(_field("pcn"), "##### 8737 #####")[1:4:2] == (True, "digits")
    # The 4-digit rule only applies to ID and card numbers.
    assert not visible(_field("full_name", "DELA CRUZ, JUAN"), "8737")[1]
    assert visible(_field("pcn"), "") == (True, False, 1.0, "cer")


def test_picture_fields_are_judged_by_opacity_only() -> None:
    img = _canvas()
    verdicts = judge_field(img, _field("face_photo", "[SYNTHETIC_AVATAR]"), _reader("x"))
    assert all(verdicts[c][:2] == (False, False) for c in CHANNELS if c != "opacity")
    assert verdicts["opacity"][0] and not verdicts["opacity"][1]  # blank square is uniform


def test_only_sensitive_fields_are_judged() -> None:
    label = {
        "document_class": "lto",
        "variant": "lto-dl-current",
        "fields": [_field("license_number"), {**_field("sex", "M"), "sensitive": False}],
    }
    results = judge_image(_canvas(), label, "img", _reader(""))
    assert {r.field_type for r in results} == {"license_number"}
    assert [r.channel for r in results] == list(CHANNELS)


def test_summary_gives_counts_and_percentages() -> None:
    label = {"document_class": "lto", "condition": "blur", "severity": 2,
             "fields": [_field("license_number"), _field("address", "1 MAIN ST")]}  # fmt: skip
    results = judge_image(_canvas(), label, "a", _reader("9117-8364-8737-7219"))
    rows = {(r["document_class"], r["condition"], r["channel"]): r for r in summarize(results)}
    visible = rows[("lto", "blur:2", "visible_pixels")]
    assert (visible["fields_judged"], visible["leaked"], visible["leak_pct"]) == (2, 1, 50.0)
    assert rows[("ALL", "ALL", "any")]["leaked"] == 1


# --- matching -------------------------------------------------------------------------


def test_missing_label_is_an_error(tmp_path: Path) -> None:
    (tmp_path / "red").mkdir()
    (tmp_path / "labels").mkdir()
    _canvas().save(tmp_path / "red" / "card_0001.jpg")
    with pytest.raises(FileNotFoundError, match="card_0001"):
        match_labels(tmp_path / "red", tmp_path / "labels")


def test_images_match_labels_by_id_and_box_files_are_ignored(tmp_path: Path) -> None:
    red, labels = tmp_path / "red" / "philsys", tmp_path / "labels"
    red.mkdir(parents=True)
    labels.mkdir()
    _canvas().save(red / "card_0001.jpg")
    (red / "card_0001.boxes.json").write_text("{}", encoding="utf-8")
    (labels / "card_0001.json").write_text("{}", encoding="utf-8")
    (labels / "card_0002.json").write_text("{}", encoding="utf-8")  # labels may outnumber images
    pairs = match_labels(tmp_path / "red", labels)
    assert [(i, p.name, lp.name) for i, p, lp in pairs] == [
        ("card_0001", "card_0001.jpg", "card_0001.json")
    ]


def test_cli_writes_leaks_and_summary(tmp_path: Path) -> None:
    data = tmp_path / "data"
    generate_dataset("lto-dl-current", 2, 5, data, composite=True)
    summary = run(data, data, tmp_path / "out", reader=_reader(""))
    with (tmp_path / "out" / "leaks.csv").open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    sensitive = sum(
        f["sensitive"]
        for p in data.glob("*.json")
        for f in json.loads(p.read_text(encoding="utf-8"))["fields"]
    )
    assert len(rows) == sensitive * len(CHANNELS)  # image x sensitive field x channel
    assert "value" not in rows[0] and "text" not in rows[0]
    with (tmp_path / "out" / "leaks_wide.csv").open(encoding="utf-8") as fh:
        wide = list(csv.DictReader(fh))
    assert len(wide) == sensitive
    assert set(CHANNELS) <= set(wide[0]) and {r["box_source"] for r in wide} == {"field"}
    assert (tmp_path / "out" / "summary.csv").exists()
    assert {"fields_judged", "leaked", "leak_pct"} <= set(summary[0])


# --- PaddleOCR on the D7 failed-redaction cases ------------------------------------------


@needs_paddle
def test_failed_redaction_cases_are_detected() -> None:
    from validation.leaktest.ocr import paddle_read

    variants = sorted(TEMPLATES)
    detected: dict[str, list[bool]] = {kind: [] for kind in FAILED_REDACTION_TYPES}
    for i in range(40):
        kind = FAILED_REDACTION_TYPES[i % len(FAILED_REDACTION_TYPES)]
        variant = variants[i % len(variants)]
        img, label = generate_single_sample(TEMPLATES[variant], seed=500 + i, composite=True)
        redacted, case = apply_failed_redaction(img, label, kind, seed=900 + i)
        targets = case["redaction"]["target_field_types"]
        results = judge_image(redacted, case, f"case{i}", paddle_read, field_types=targets)
        detected[kind].append(any(r.leaked for r in results))

    assert not any(detected["complete"]), "a complete redaction was reported as a leak"
    for kind in ("partial", "misplaced", "ineffective"):
        rate = sum(detected[kind]) / len(detected[kind])
        assert rate >= 0.95, f"{kind}: detected {rate:.0%}"


@needs_paddle
def test_unredacted_text_leaks_through_the_visible_channel() -> None:
    from validation.leaktest.ocr import paddle_read

    img, label = generate_single_sample(TEMPLATES["philsys-card"], seed=3, composite=False)
    results = judge_image(img.convert("RGB"), label, "clean", paddle_read)
    visible = {
        r.field_type: r.leaked for r in results if r.channel == "visible_pixels" and r.judged
    }
    assert visible and all(visible.values()), visible


def test_cli_rejects_missing_folder(tmp_path: Path) -> None:
    with pytest.raises(SystemExit):
        main([str(tmp_path / "nope"), "--labels", str(tmp_path)])
