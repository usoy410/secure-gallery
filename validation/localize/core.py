"""Desktop reference localization: OCR-first, layout-only, and hybrid (§5.1, §9.3).

layout_only maps each sensitive template field through the homography from the
template's unit square to the card corners. ocr_first reads the document with
Tesseract and matches the shared regex rules in templates/rules/; it knows nothing
about card layout. hybrid takes the union of both, so a box is only ever added,
never shrunk. A document with no usable corners and no OCR match falls back to
redacting the whole image.

Nothing here stores OCR text. Boxes carry only a field type, a source and a polygon.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageOps

from ml.conditions import _solve_perspective_system
from ml.payment_card import is_luhn_valid

REPO_ROOT = Path(__file__).resolve().parents[2]
TEMPLATES_DIR = REPO_ROOT / "templates"
RULES_PATH = TEMPLATES_DIR / "rules" / "fields.json"

STRATEGIES = ("ocr_first", "layout_only", "hybrid")
FILL = (0, 0, 0)
JPEG_QUALITY = 95
OCR_PAD = 4  # pixels added around each OCR word box, in rectified card space
RECTIFIED_WIDTH = 1400  # OCR reads the card at this width after rectification
# Faces, QR codes and signatures are matched by position only, so their layout box gets
# a margin on every side, as a share of the card width. Printed fields drift up to 2% of
# the card width plus a 0.8% print offset (ml/jitter.py), and #4 accepts templates within
# 3% of the real card. Text fields need none: their boxes already cover the text.
PICTURE_FIELDS = frozenset({"face_photo", "qr_code", "signature"})
PICTURE_MARGIN = 0.03

Point = tuple[float, float]


@dataclass(frozen=True)
class Box:
    field_type: str
    source: str  # "layout", "ocr" or "fallback"
    polygon: tuple[Point, ...]

    def to_json(self) -> dict[str, Any]:
        return {
            "field_type": self.field_type,
            "source": self.source,
            "polygon": [[round(x, 1), round(y, 1)] for x, y in self.polygon],
        }


@dataclass(frozen=True)
class Result:
    boxes: tuple[Box, ...]
    fallback: bool
    corners: tuple[Point, ...] | None


# --- geometry ---------------------------------------------------------------


def complete_corners(raw: Any) -> tuple[Point, ...] | None:
    """Return four corners (TL, TR, BR, BL), or None if fewer than three are known.

    One missing corner (None) is filled in as a parallelogram from the other three.
    """
    if not isinstance(raw, list | tuple) or len(raw) != 4:
        return None
    pts: list[Point | None] = [
        (float(p[0]), float(p[1])) if isinstance(p, list | tuple) and len(p) == 2 else None
        for p in raw
    ]
    missing = [i for i, p in enumerate(pts) if p is None]
    if len(missing) > 1:
        return None
    if missing:
        i = missing[0]
        prev_pt, opposite, next_pt = pts[(i - 1) % 4], pts[(i + 2) % 4], pts[(i + 1) % 4]
        assert prev_pt and opposite and next_pt
        pts[i] = (prev_pt[0] + next_pt[0] - opposite[0], prev_pt[1] + next_pt[1] - opposite[1])
    return tuple(p for p in pts if p is not None)


def homography(src: tuple[Point, ...], dst: tuple[Point, ...]) -> tuple[float, ...]:
    """Coefficients mapping each src corner onto the matching dst corner."""
    return _solve_perspective_system(list(dst), list(src))  # type: ignore[arg-type]


def project(coeffs: tuple[float, ...], pt: Point) -> Point:
    a, b, c, d, e, f, g, h = coeffs
    x, y = pt
    w = g * x + h * y + 1.0
    return (a * x + b * y + c) / w, (d * x + e * y + f) / w


# --- layout_only --------------------------------------------------------------

UNIT_SQUARE: tuple[Point, ...] = ((0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0))


def load_templates() -> dict[str, dict[str, Any]]:
    templates = {}
    for path in sorted(TEMPLATES_DIR.glob("*.json")):
        if path.name != "schema.json":
            data = json.loads(path.read_text(encoding="utf-8"))
            templates[data["id"]] = data
    return templates


def padded_polygon(field: dict[str, Any], aspect_ratio: float) -> list[Point]:
    """The field's polygon in unit-square card space, widened if it is a picture field.

    The margin is PICTURE_MARGIN of the card width on all four sides; in unit-square
    units that is PICTURE_MARGIN across and PICTURE_MARGIN * aspect_ratio down.
    """
    polygon = [(float(x), float(y)) for x, y in field["polygon"]]
    if field["type"] not in PICTURE_FIELDS:
        return polygon
    xs, ys = [x for x, _ in polygon], [y for _, y in polygon]
    mx, my = PICTURE_MARGIN, PICTURE_MARGIN * aspect_ratio
    left, top, right, bottom = min(xs) - mx, min(ys) - my, max(xs) + mx, max(ys) + my
    return [(left, top), (right, top), (right, bottom), (left, bottom)]


def layout_boxes(template: dict[str, Any], corners: tuple[Point, ...]) -> list[Box]:
    """Map every sensitive template field into the image through the card homography."""
    coeffs = homography(UNIT_SQUARE, corners)
    aspect = float(template.get("aspect_ratio", 1.0))
    return [
        Box(f["type"], "layout", tuple(project(coeffs, p) for p in padded_polygon(f, aspect)))
        for f in template["fields"]
        if f["sensitive"]
    ]


# --- ocr_first ----------------------------------------------------------------


@dataclass(frozen=True)
class Rule:
    field_type: str
    pattern: re.Pattern[str]
    luhn: bool
    skip_if_excluded: bool
    extend_next_line: bool


def load_rules(path: Path = RULES_PATH) -> tuple[list[Rule], frozenset[str]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    rules = [
        Rule(
            field_type=r["field_type"],
            pattern=re.compile(r["pattern"]),
            luhn=r.get("validate") == "luhn",
            skip_if_excluded=r.get("skip_if_excluded_word", False),
            extend_next_line=r.get("extend_next_line", False),
        )
        for r in data["rules"]
    ]
    return rules, frozenset(data.get("exclude_words", []))


@dataclass
class _Word:
    text: str
    box: tuple[int, int, int, int]  # left, top, right, bottom
    start: int = 0  # character offset in its line


def _ocr_lines(img: Image.Image) -> list[tuple[tuple[int, int, int], list[_Word]]]:
    """Tesseract words grouped into lines, keyed by (block, paragraph, line)."""
    import pytesseract

    data = pytesseract.image_to_data(img, config="--psm 11", output_type=pytesseract.Output.DICT)
    lines: dict[tuple[int, int, int], list[_Word]] = {}
    for i, text in enumerate(data["text"]):
        text = text.strip()
        if not text or float(data["conf"][i]) < 0:
            continue
        key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
        left, top = data["left"][i], data["top"][i]
        lines.setdefault(key, []).append(
            _Word(text, (left, top, left + data["width"][i], top + data["height"][i]))
        )
    ordered = []
    for key in sorted(lines, key=lambda k: (min(w.box[1] for w in lines[k]), k)):
        words = sorted(lines[key], key=lambda w: w.box[0])
        offset = 0
        for w in words:
            w.start = offset
            offset += len(w.text) + 1
        ordered.append((key, words))
    return _merge_rows(ordered)


def _merge_rows(
    lines: list[tuple[tuple[int, int, int], list[_Word]]],
) -> list[tuple[tuple[int, int, int], list[_Word]]]:
    """Sparse-text mode can split one printed line into several blocks. Join pieces
    that share a row and sit close together, so multi-word values match as one line."""
    merged: list[tuple[tuple[int, int, int], list[_Word]]] = []
    for key, words in lines:
        if merged:
            prev = merged[-1][1]
            height = max(w.box[3] - w.box[1] for w in prev)
            same_row = abs(prev[0].box[1] - words[0].box[1]) < height * 0.5
            gap = words[0].box[0] - prev[-1].box[2]
            if same_row and 0 <= gap < height * 1.5:
                combined = prev + words
                offset = 0
                for w in combined:
                    w.start = offset
                    offset += len(w.text) + 1
                merged[-1] = (merged[-1][0], combined)
                continue
        merged.append((key, words))
    return merged


def _union_box(words: list[_Word]) -> tuple[int, int, int, int]:
    return (
        min(w.box[0] for w in words),
        min(w.box[1] for w in words),
        max(w.box[2] for w in words),
        max(w.box[3] for w in words),
    )


def find_fields(
    img: Image.Image, rules: list[Rule], excluded: frozenset[str], pad: int = OCR_PAD
) -> list[Box]:
    """Run the rules over every OCR line. Boxes are in the coordinates of ``img``."""
    gray = ImageOps.grayscale(img)
    found: list[Box] = []
    passes = [gray]
    if sum(gray.resize((32, 32)).getdata()) / 1024 < 110:
        # Dark card: most text is light, but panels (e.g. the CVV strip) hold dark text.
        passes.append(ImageOps.invert(gray))
    for page in passes:
        found += _match_lines(_ocr_lines(page), rules, excluded, pad)
    return found


def _match_lines(
    lines: list[tuple[tuple[int, int, int], list[_Word]]],
    rules: list[Rule],
    excluded: frozenset[str],
    pad: int = OCR_PAD,
) -> list[Box]:
    found: list[Box] = []
    for idx, (key, words) in enumerate(lines):
        text = " ".join(w.text for w in words).upper()
        has_excluded = any(w.strip(",.").upper() in excluded for w in text.split())
        for rule in rules:
            if rule.skip_if_excluded and has_excluded:
                continue
            for m in rule.pattern.finditer(text):
                if rule.luhn and not is_luhn_valid(m.group(0)):
                    continue
                hit = [w for w in words if w.start < m.end() and w.start + len(w.text) > m.start()]
                if rule.extend_next_line and idx + 1 < len(lines):
                    next_key, next_words = lines[idx + 1]
                    if next_key[:2] == key[:2]:
                        hit = hit + next_words
                x0, y0, x1, y1 = _union_box(hit)
                x0, y0, x1, y1 = x0 - pad, y0 - pad, x1 + pad, y1 + pad
                found.append(Box(rule.field_type, "ocr", ((x0, y0), (x1, y0), (x1, y1), (x0, y1))))
    return found


def ocr_boxes(
    image: Image.Image,
    corners: tuple[Point, ...] | None,
    rules: list[Rule],
    excluded: frozenset[str],
    pad: int = OCR_PAD,
) -> list[Box]:
    """OCR the document region. With corners, the card is first rectified to an upright
    rectangle (no template is used) and the boxes are mapped back into the image."""
    if corners is None:
        return find_fields(image.convert("RGB"), rules, excluded, pad)

    top = ((corners[1][0] - corners[0][0]) ** 2 + (corners[1][1] - corners[0][1]) ** 2) ** 0.5
    side = ((corners[3][0] - corners[0][0]) ** 2 + (corners[3][1] - corners[0][1]) ** 2) ** 0.5
    width = RECTIFIED_WIDTH
    height = max(1, round(width * side / max(top, 1.0)))
    rect: tuple[Point, ...] = ((0, 0), (width, 0), (width, height), (0, height))
    to_image = homography(rect, corners)
    upright = image.convert("RGB").transform(
        (width, height), Image.Transform.PERSPECTIVE, to_image, Image.Resampling.BICUBIC
    )
    return [
        Box(b.field_type, b.source, tuple(project(to_image, p) for p in b.polygon))
        for b in find_fields(upright, rules, excluded, pad)
    ]


# --- strategies -------------------------------------------------------------


def localize(
    image: Image.Image,
    strategy: str,
    *,
    is_document: bool,
    template: dict[str, Any] | None,
    raw_corners: Any,
    rules: list[Rule],
    excluded: frozenset[str],
    ocr_pad: int = OCR_PAD,
) -> Result:
    """Return the redaction boxes one strategy produces for one image.

    ``is_document`` says whether a supported card is present (ground truth, or the
    detector in model mode). The whole-image fallback applies only to documents.
    ``ocr_pad`` widens each OCR box (pixels in rectified card space); RQ4 tunes it on val.
    """
    if strategy not in STRATEGIES:
        raise ValueError(f"Unknown strategy {strategy!r}; choose from {STRATEGIES}")
    corners = complete_corners(raw_corners) if is_document else None

    boxes: list[Box] = []
    if strategy in ("layout_only", "hybrid") and corners and template:
        boxes += layout_boxes(template, corners)
    ocr_found: list[Box] = []
    if strategy in ("ocr_first", "hybrid"):
        ocr_found = ocr_boxes(image, corners, rules, excluded, ocr_pad)
        boxes += ocr_found

    fallback = is_document and corners is None and not ocr_found
    if fallback:
        w, h = image.size
        boxes = [Box("document", "fallback", ((0, 0), (w, 0), (w, h), (0, h)))]
    return Result(tuple(boxes), fallback, corners)


def redact(image: Image.Image, boxes: tuple[Box, ...] | list[Box]) -> Image.Image:
    """Solid opaque fill over every box. The pixels underneath are replaced, not covered."""
    out = image.convert("RGB")
    draw = ImageDraw.Draw(out)
    for box in boxes:
        draw.polygon(box.polygon, fill=FILL, outline=FILL)
    return out


def save_jpeg(image: Image.Image, path: Path) -> None:
    """JPEG at quality 95, like the app's saved copy. No EXIF is written."""
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="JPEG", quality=JPEG_QUALITY)
