"""Tests for the EXIF-thumbnail, metadata and number-structure channels (issue #13)."""

from __future__ import annotations

import importlib.util
import io
import os
import struct
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from ml.generate import generate_single_sample, get_available_templates
from validation.leaktest.core import judge_field, judge_image
from validation.leaktest.filemeta import read_file_meta, scale_polygon
from validation.leaktest.numbers import (
    CANDIDATE_LEAK_MAX,
    luhn_candidates,
    recovered_positions,
    remaining_candidates,
)

TEMPLATES = get_available_templates()

_has_paddle = importlib.util.find_spec("paddleocr") is not None
needs_paddle = pytest.mark.skipif(
    not _has_paddle and not os.environ.get("CI"), reason="PaddleOCR not installed"
)


# --- fixture builder ----------------------------------------------------------------------


def build_exif(
    thumbnail: bytes | None = None,
    make: str | None = None,
    model: str | None = None,
    gps: bool = False,
) -> bytes:
    """A little-endian EXIF block: IFD0 tags, an optional GPS IFD, an optional IFD1 thumbnail."""
    entries: list[tuple[int, int, int, bytes]] = []  # tag, type, count, data
    for tag, text in ((0x010F, make), (0x0110, model)):
        if text is not None:
            data = text.encode() + b"\x00"
            entries.append((tag, 2, len(data), data))
    gps_ifd = struct.pack("<HHHI4s", 1, 0x0000, 1, 4, b"\x02\x03\x00\x00") + struct.pack("<I", 0)
    if gps:
        entries.append((0x8825, 4, 1, b""))

    ifd0_size = 2 + 12 * len(entries) + 4
    cursor = 8 + ifd0_size
    blobs = b""
    packed = b""
    gps_offset = None
    for tag, typ, count, data in entries:
        if tag == 0x8825:
            gps_offset = 0  # filled in below, once the blobs are placed
            packed += struct.pack("<HHI", tag, typ, count) + b"GPS!"
        elif len(data) <= 4:
            packed += struct.pack("<HHI", tag, typ, count) + data.ljust(4, b"\x00")
        else:
            packed += struct.pack("<HHII", tag, typ, count, cursor + len(blobs))
            blobs += data
    if gps_offset is not None:
        gps_offset = cursor + len(blobs)
        packed = packed.replace(b"GPS!", struct.pack("<I", gps_offset))
        blobs += gps_ifd
    ifd1_offset = cursor + len(blobs) if thumbnail else 0
    tiff = b"II*\x00" + struct.pack("<I", 8) + struct.pack("<H", len(entries)) + packed
    tiff += struct.pack("<I", ifd1_offset) + blobs
    if thumbnail:
        thumb_offset = ifd1_offset + 2 + 2 * 12 + 4
        tiff += struct.pack("<H", 2)
        tiff += struct.pack("<HHII", 0x0201, 4, 1, thumb_offset)
        tiff += struct.pack("<HHII", 0x0202, 4, 1, len(thumbnail))
        tiff += struct.pack("<I", 0) + thumbnail
    return b"Exif\x00\x00" + tiff


def _jpeg_bytes(img: Image.Image, width: int, quality: int = 85) -> bytes:
    small = img.convert("RGB").resize((width, round(img.height * width / img.width)))
    buf = io.BytesIO()
    small.save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def _redacted_card() -> tuple[Image.Image, Image.Image, dict]:
    """(clean card, the same card with every sensitive field solid-filled, its label)."""
    img, label = generate_single_sample(TEMPLATES["philsys-card"], seed=6, composite=False)
    clean = img.convert("RGB")
    redacted = clean.copy()
    draw = ImageDraw.Draw(redacted)
    for field in label["fields"]:
        if field["sensitive"]:
            draw.polygon([tuple(p) for p in field["polygon"]], fill=(0, 0, 0))
    return clean, redacted, label


# --- reading the file ------------------------------------------------------------------------


def test_thumbnail_and_tags_are_read_but_values_never_reported(tmp_path: Path) -> None:
    clean, redacted, _ = _redacted_card()
    xmp = (
        b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description '
        b'xmpMM:PreservedFileName="IMG_2041.JPG"><xmpMM:History>edit</xmpMM:History>'
        b"</rdf:Description></rdf:RDF></x:xmpmeta>"
    )
    path = tmp_path / "leaky.jpg"
    exif = build_exif(_jpeg_bytes(clean, 400), make="TestPhoneMaker", model="TP-100", gps=True)
    redacted.save(path, format="JPEG", quality=95, exif=exif, xmp=xmp)

    meta = read_file_meta(path)
    assert meta.thumbnail is not None and meta.thumbnail.width == 400
    assert meta.tags == (
        "GPSInfo", "Make", "Model", "XMP:xmpMM:History", "XMP:xmpMM:PreservedFileName",
    )  # fmt: skip
    rule = judge_image(redacted, {"fields": []}, "leaky", lambda c: "", source=path)[0].rule
    assert "TestPhoneMaker" not in rule and "IMG_2041" not in rule


def test_stripped_file_has_no_thumbnail_and_no_tags(tmp_path: Path) -> None:
    _, redacted, label = _redacted_card()
    path = tmp_path / "stripped.jpg"
    redacted.save(path, format="JPEG", quality=95)
    meta = read_file_meta(path)
    assert meta.thumbnail is None and meta.tags == ()
    results = judge_image(redacted, label, "stripped", lambda c: "", source=path)
    metadata = [r for r in results if r.channel == "metadata"]
    assert len(metadata) == 1 and not metadata[0].leaked and metadata[0].field_type == "image"
    assert not any(r.judged for r in results if r.channel == "exif_thumbnail")


def test_polygons_scale_to_the_thumbnail() -> None:
    assert scale_polygon([[100, 50], [200, 50]], (1000, 500), (250, 125)) == [
        [25.0, 12.5],
        [50.0, 12.5],
    ]


@needs_paddle
def test_unredacted_thumbnail_leaks_and_redacted_thumbnail_does_not(tmp_path: Path) -> None:
    from validation.leaktest.ocr import paddle_read

    clean, redacted, label = _redacted_card()
    cases = {"stale": clean, "updated": redacted}  # thumbnail made before / after redaction
    for name, thumb_source in cases.items():
        path = tmp_path / f"{name}.jpg"
        exif = build_exif(_jpeg_bytes(thumb_source, 480))
        redacted.save(path, format="JPEG", quality=95, exif=exif)
        results = judge_image(redacted, label, name, paddle_read, source=path)
        thumb = {
            r.field_type: r.leaked for r in results if r.channel == "exif_thumbnail" and r.judged
        }
        assert thumb, name
        if name == "stale":
            assert thumb["pcn"] and thumb["date_of_birth"], thumb
        else:
            assert not any(thumb.values()), thumb


# --- number structure ------------------------------------------------------------------------


def test_luhn_candidate_counts() -> None:
    assert luhn_candidates(16, {}) == 10**15
    assert luhn_candidates(16, {p: 4 for p in range(15)}) == 1
    # Prefix (6) + last 4 known: 6 unknown digits, one in ten passes Luhn.
    prefix_and_last_four = {**{p: 4 for p in range(6)}, **{p: 2 for p in range(12, 16)}}
    assert luhn_candidates(16, prefix_and_last_four) == 10**5


def test_recovered_digits_are_placed_by_alignment() -> None:
    assert recovered_positions("4000056655665556", "#### #### #### 5556") == {12, 13, 14, 15}
    assert recovered_positions("4000056655665556", "") == set()
    assert recovered_positions("4000056655665556", "x 9 x") == set()  # single digits are chance


def test_number_structure_threshold() -> None:
    value = "4000 0566 5566 5556"
    # BIN + last 4 showing: 100,000 Luhn-valid numbers left, not a leak.
    assert remaining_candidates(value, "4000 05## #### 5556") == (10, 10**5)
    # One hidden group of 4: 1,000 left, which is at the leak limit.
    assert remaining_candidates(value, "4000 0566 #### 5556") == (12, 1000)
    assert 1000 <= CANDIDATE_LEAK_MAX
    # Two of those digits also showing: 10 left.
    assert remaining_candidates(value, "4000 0566 55## 5556") == (14, 10)


def test_number_structure_channel_only_judges_card_numbers_with_digits() -> None:
    img = Image.new("RGB", (200, 100), (0, 0, 0))
    field = {
        "type": "card_number", "name": "PAN", "sensitive": True,
        "value": "4000 0566 5566 5556", "polygon": [[10, 10], [190, 10], [190, 90], [10, 90]],
    }  # fmt: skip

    def verdict(text: str) -> tuple:
        return judge_field(img, field, lambda c: text)["number_structure"]

    assert verdict("4000 0566 #### 5556")[:2] == (True, True)
    assert verdict("#### #### #### 5556")[:2] == (True, False)
    assert verdict("")[:2] == (False, False)
    pcn = {**field, "type": "pcn"}
    assert judge_field(img, pcn, lambda c: "1234")["number_structure"][3] == "not_card_number"
