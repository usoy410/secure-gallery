"""Leak-test core: visible-pixel and opacity channels (SECURE_GALLERY.md §9.2, §9.5).

Every redacted image is judged against its ground-truth label, field by field:

- visible_pixels: an independent OCR engine (PaddleOCR; the localizer uses Tesseract,
  so no engine grades itself) reads the field polygon. The field leaks if the
  character error rate against the true value is <= 0.3, or if any 4 consecutive
  digits of an ID or card number come back.
- opacity: inside the field polygon (minus a 3 px rim), the share of pixels within
  8 of the polygon's median colour on every channel. Below 99.5% the field fails:
  the cover is not a solid fill, or it does not cover the whole field.

The partial_chars, non_opaque_fill and mosaic_blur channels live in channels.py;
exif_thumbnail and metadata in filemeta.py; number_structure in numbers.py.
Only sensitive fields are judged. OCR text is used in memory and never written out.
"""

from __future__ import annotations

import csv
import json
import re
import statistics
from collections.abc import Callable, Iterable
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFilter

from ml.conditions import _solve_perspective_system

CER_LEAK_MAX = 0.3
DIGIT_RUN = 4
OPACITY_TOLERANCE = 8
OPACITY_MIN_SHARE = 0.995
# Ignore a thin rim at the polygon border. Labels are rounded to whole pixels, the edge
# is anti-aliased, and JPEG rings next to a black fill, so a correct cover still shows
# a 1-2 px seam there. Text inside the rim is still caught by the visible-pixel channel.
OPACITY_EDGE_PX = 3

# Picture fields: OCR has nothing to compare, so only the opacity channel judges them.
PICTURE_FIELDS = frozenset({"face_photo", "signature", "qr_code"})
# ID and card numbers, where 4 recovered digits already count as a leak (§9.2).
NUMBER_FIELDS = frozenset({"pcn", "pcn_psn", "license_number", "card_number"})

FIELD_CHANNELS = (
    "visible_pixels",
    "opacity",
    "partial_chars",
    "non_opaque_fill",
    "mosaic_blur",
    "exif_thumbnail",
    "number_structure",
)
# Judged once per file, reported on one row with field_type "image".
IMAGE_CHANNELS = ("metadata",)
CHANNELS = FIELD_CHANNELS + IMAGE_CHANNELS
THUMB_MIN_TEXT_PX = 48  # thumbnail field crops are enlarged to this height before OCR
# Mosaic or blur over text is a recorded risk, not a recovered leak, so the "any" summary
# row leaves it out; it is still reported on its own row.
RISK_CHANNELS = frozenset({"mosaic_blur"})
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png", ".webp")

Reader = Callable[[Image.Image], str]


@dataclass(frozen=True)
class FieldResult:
    image_id: str
    document_class: str
    variant: str
    condition: str
    field_type: str
    field_name: str
    box_source: str  # where the redaction boxes came from: "file", "label" or "field"
    channel: str
    judged: bool  # False when the channel does not apply (OCR on a picture field)
    leaked: bool
    score: float  # CER for visible_pixels, uniform share for opacity
    rule: str  # what decided the outcome: "cer", "digits", "uniform", "not_text"


# --- text measures -------------------------------------------------------------


def _normalize(text: str) -> str:
    return " ".join(text.upper().split())


def substring_distance(text: str, truth: str) -> int:
    """Fewest edits that turn some substring of ``text`` into ``truth``.

    Text around the value (a neighbouring field, the SPECIMEN watermark) costs nothing,
    so extra words read from the same crop can never hide a leak.
    """
    prev = [0] * (len(text) + 1)  # a match may start anywhere in text
    for i, ct in enumerate(truth, 1):
        cur = [i]
        for j, cx in enumerate(text, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ct != cx)))
        prev = cur
    return min(prev)  # ... and end anywhere


def cer(recovered: str, truth: str) -> float:
    """Character error rate of the best-matching part of the OCR text against the value."""
    truth_n = _normalize(truth)
    if not truth_n:
        return 1.0
    return substring_distance(_normalize(recovered), truth_n) / len(truth_n)


def digit_run_recovered(recovered: str, truth: str, run: int = DIGIT_RUN) -> bool:
    """True if any ``run`` consecutive digits of the true number appear in the OCR text.

    Spaces and dashes between digits are ignored, so "4242 4242" counts as one run.
    """
    truth_digits = re.sub(r"\D", "", truth)
    if len(truth_digits) < run:
        return False
    runs = re.findall(r"\d+", re.sub(r"(?<=\d)[ \-](?=\d)", "", recovered))
    windows = {truth_digits[i : i + run] for i in range(len(truth_digits) - run + 1)}
    return any(w in r for r in runs for w in windows)


# --- pixel measures -------------------------------------------------------------


def polygon_mask(size: tuple[int, int], polygon: list) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).polygon([tuple(p) for p in polygon], fill=255)
    return mask


def uniform_share(image: Image.Image, polygon: list) -> float:
    """Share of polygon pixels within OPACITY_TOLERANCE of the median on every channel."""
    rgb = image.convert("RGB")
    mask = polygon_mask(rgb.size, polygon)
    inner = mask.filter(ImageFilter.MinFilter(2 * OPACITY_EDGE_PX + 1))
    if inner.getbbox() is not None:  # keep tiny polygons whole rather than empty
        mask = inner
    box = mask.getbbox()
    if box is None:
        return 1.0
    pixels = [
        p
        for p, m in zip(rgb.crop(box).getdata(), mask.crop(box).getdata(), strict=True)
        if m
    ]
    if not pixels:
        return 1.0
    median = [statistics.median(p[c] for p in pixels) for c in range(3)]
    close = sum(
        all(abs(p[c] - median[c]) <= OPACITY_TOLERANCE for c in range(3)) for p in pixels
    )
    return close / len(pixels)


def field_crop(image: Image.Image, polygon: list) -> Image.Image:
    """The field as an upright rectangle: four-point polygons are rectified, others are
    cut out by their bounding box with everything outside the polygon set to white."""
    rgb = image.convert("RGB")
    if len(polygon) == 4:
        (x0, y0), (x1, y1), (x2, y2), (x3, y3) = [(float(p[0]), float(p[1])) for p in polygon]
        width = max(8, round(max(((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5,
                                 ((x2 - x3) ** 2 + (y2 - y3) ** 2) ** 0.5)))  # fmt: skip
        height = max(8, round(max(((x3 - x0) ** 2 + (y3 - y0) ** 2) ** 0.5,
                                  ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5)))  # fmt: skip
        rect = [(0, 0), (width, 0), (width, height), (0, height)]
        coeffs = _solve_perspective_system([tuple(p) for p in polygon], rect)  # type: ignore[arg-type]
        return rgb.transform(
            (width, height), Image.Transform.PERSPECTIVE, coeffs, Image.Resampling.BICUBIC
        )
    mask = polygon_mask(rgb.size, polygon)
    box = mask.getbbox() or (0, 0, 1, 1)
    out = Image.new("RGB", (box[2] - box[0], box[3] - box[1]), (255, 255, 255))
    out.paste(rgb.crop(box), (0, 0), mask.crop(box))
    return out


# --- judging --------------------------------------------------------------------


def text_verdict(recovered: str, field: dict[str, Any]) -> tuple[bool, float, str]:
    """The §9.2 rule: (leaked, CER, deciding rule) for OCR text read from a field."""
    truth = str(field.get("value", ""))
    rate = round(cer(recovered, truth), 4)
    if rate <= CER_LEAK_MAX:
        return True, rate, "cer"
    if field["type"] in NUMBER_FIELDS and digit_run_recovered(recovered, truth):
        return True, rate, "digits"
    return False, rate, "cer"


Verdict = tuple[bool, bool, float, str]  # judged, leaked, score, rule


def judge_field(
    image: Image.Image,
    field: dict[str, Any],
    reader: Reader,
    boxes: list | None = None,
    meta: Any = None,
) -> dict[str, Verdict]:
    """(judged, leaked, score, rule) per field channel for one field.

    ``boxes`` are the redaction boxes drawn on the image (any that miss the field are
    ignored). None means they are unknown and the field polygon stands in for them.
    ``meta`` is the file's FileMeta (filemeta.py); without it the thumbnail channel
    is not judged.
    """
    from validation.leaktest import channels as ch
    from validation.leaktest.numbers import CANDIDATE_LEAK_MAX, remaining_candidates

    polygon = field["polygon"]
    share = uniform_share(image, polygon)
    verdicts: dict[str, Verdict] = {
        "opacity": (True, share < OPACITY_MIN_SHARE, round(share, 4), "uniform")
    }
    if field["type"] in PICTURE_FIELDS:
        not_text = (False, False, 1.0, "not_text")
        verdicts.update({c: not_text for c in FIELD_CHANNELS if c != "opacity"})
        return verdicts

    recovered = reader(field_crop(image, polygon))
    leaked, rate, rule = text_verdict(recovered, field)
    verdicts["visible_pixels"] = (True, leaked, rate, rule)

    # Card-number structure: Luhn-valid numbers left given the digits that came back.
    verdicts["number_structure"] = (False, False, 0.0, "not_card_number")
    if field["type"] == "card_number":
        found, candidates = remaining_candidates(str(field.get("value", "")), recovered)
        if found:
            verdicts["number_structure"] = (
                True, candidates <= CANDIDATE_LEAK_MAX, float(candidates), "candidates"
            )  # fmt: skip
        else:
            verdicts["number_structure"] = (False, False, 0.0, "no_digits")

    # Embedded EXIF thumbnail: the same visible-pixel test on the scaled-down field.
    verdicts["exif_thumbnail"] = (False, False, 1.0, "no_thumbnail")
    if meta is not None and meta.thumbnail is not None:
        from validation.leaktest.filemeta import scale_polygon

        thumb_poly = scale_polygon(polygon, meta.size, meta.thumbnail.size)
        crop = field_crop(meta.thumbnail, thumb_poly)
        if crop.height < THUMB_MIN_TEXT_PX:
            factor = THUMB_MIN_TEXT_PX / crop.height
            crop = crop.resize(
                (round(crop.width * factor), THUMB_MIN_TEXT_PX), Image.Resampling.BICUBIC
            )
        leaked, rate, rule = text_verdict(reader(crop), field)
        verdicts["exif_thumbnail"] = (True, leaked, rate, rule)

    if boxes is None:
        over = [ch.as_polygon(polygon)]
    else:
        over = ch.boxes_over_field(image.size, polygon, [ch.as_polygon(b) for b in boxes])
    if not over:  # nothing drawn over the field: visible_pixels and opacity already judge it
        no_box = (False, False, 1.0, "no_box")
        verdicts.update(partial_chars=no_box, non_opaque_fill=no_box, mosaic_blur=no_box)
        return verdicts

    def judge(text: str) -> tuple[bool, float, str]:
        return text_verdict(text, field)

    for name, outcome in (
        ("partial_chars", ch.partial_chars(image, over, reader, judge)),
        ("non_opaque_fill", ch.non_opaque_fill(image, over, reader, judge)),
        ("mosaic_blur", ch.mosaic_blur(image, over)),
    ):
        verdicts[name] = (outcome.judged, outcome.leaked, outcome.score, outcome.rule)
    return verdicts


def load_boxes(image_path: Path, label: dict[str, Any]) -> tuple[list | None, str]:
    """The redaction boxes for an image and where they came from.

    1. <image id>.boxes.json beside the image (written by validation.localize);
    2. the label's redaction.redaction_boxes (D7 failed-redaction cases);
    3. None: unknown, so each field polygon stands in for its box.
    """
    box_file = image_path.with_name(image_path.stem + ".boxes.json")
    if box_file.exists():
        record = json.loads(box_file.read_text(encoding="utf-8"))
        return [b["polygon"] for b in record.get("boxes", [])], "file"
    drawn = (label.get("redaction") or {}).get("redaction_boxes")
    if drawn is not None:
        return list(drawn), "label"
    return None, "field"


def judge_image(
    image: Image.Image,
    label: dict[str, Any],
    image_id: str,
    reader: Reader,
    field_types: Iterable[str] | None = None,
    boxes: list | None = None,
    box_source: str = "field",
    source: Path | None = None,
) -> list[FieldResult]:
    """Judge every sensitive field (or only ``field_types``) of one redacted image.

    ``source`` is the redacted file on disk. With it, the EXIF thumbnail is judged per
    field and the file's metadata on one extra row (field_type "image").
    """
    from validation.leaktest.filemeta import read_file_meta

    meta = read_file_meta(source) if source is not None else None
    wanted = set(field_types) if field_types is not None else None
    condition = label.get("condition") or "none"
    if label.get("severity") not in (None, ""):
        condition = f"{condition}:{label['severity']}"
    document_class = label.get("document_class") or label.get("class", "")
    results = []
    for field in label.get("fields", []):
        if not field.get("sensitive") or not field.get("polygon"):
            continue
        if wanted is not None and field["type"] not in wanted:
            continue
        verdicts = judge_field(image, field, reader, boxes, meta)
        for channel in FIELD_CHANNELS:
            judged, leaked, score, rule = verdicts[channel]
            results.append(
                FieldResult(
                    image_id=image_id,
                    document_class=document_class,
                    variant=label.get("variant", ""),
                    condition=condition,
                    field_type=field["type"],
                    field_name=field.get("name", ""),
                    box_source=box_source if boxes is not None else "field",
                    channel=channel,
                    judged=judged,
                    leaked=leaked,
                    score=score,
                    rule=rule,
                )
            )
    if meta is not None:
        results.append(
            FieldResult(
                image_id=image_id,
                document_class=document_class,
                variant=label.get("variant", ""),
                condition=condition,
                field_type="image",
                field_name="",
                box_source="file",
                channel="metadata",
                judged=True,
                leaked=bool(meta.tags),
                score=float(len(meta.tags)),
                rule=";".join(meta.tags) or "none",  # tag names only, never values
            )
        )
    return results


# --- dataset I/O -----------------------------------------------------------------


def match_labels(redacted_dir: Path, labels_dir: Path) -> list[tuple[str, Path, Path]]:
    """Pair each redacted image with its label by id (the file name without suffix).

    A redacted image without a label is an error, never a silent skip.
    """
    labels: dict[str, Path] = {}
    for path in labels_dir.rglob("*.json"):
        if path.name.endswith(".boxes.json"):
            continue
        if path.stem in labels:
            raise ValueError(f"Duplicate label id {path.stem!r}: {labels[path.stem]} and {path}")
        labels[path.stem] = path
    pairs = []
    images = sorted(p for p in redacted_dir.rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)
    if not images:
        raise ValueError(f"No images found under {redacted_dir}")
    for image_path in images:
        image_id = image_path.stem
        if image_id not in labels:
            raise FileNotFoundError(f"No label for image id {image_id!r} ({image_path})")
        pairs.append((image_id, image_path, labels[image_id]))
    return pairs


def write_leaks(results: list[FieldResult], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    columns = list(FieldResult.__dataclass_fields__)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=columns)
        writer.writeheader()
        for r in results:
            writer.writerow(asdict(r))


def summarize(results: list[FieldResult]) -> list[dict[str, Any]]:
    """Leak counts and percentages per class, condition and channel.

    The "any" channel counts a field as leaked when any judged channel recovered it;
    mosaic_blur is a risk flag and is reported only on its own row.
    Rows with class or condition "ALL" aggregate across that dimension.
    """
    per_field: dict[tuple[str, str, str, str], list[FieldResult]] = {}
    for r in results:
        key = (r.image_id, r.field_type, r.field_name, r.document_class)
        per_field.setdefault(key, []).append(r)

    rows: list[tuple[str, str, str, bool]] = []  # class, condition, channel, leaked
    for group in per_field.values():
        for r in group:
            if r.judged:
                rows.append((r.document_class, r.condition, r.channel, r.leaked))
        first = group[0]
        rows.append(
            (
                first.document_class,
                first.condition,
                "any",
                any(r.leaked for r in group if r.judged and r.channel not in RISK_CHANNELS),
            )
        )

    table: dict[tuple[str, str, str], list[int]] = {}
    for doc_class, condition, channel, leaked in rows:
        for c in (doc_class, "ALL"):
            for cond in (condition, "ALL"):
                counts = table.setdefault((c, cond, channel), [0, 0])
                counts[0] += 1
                counts[1] += int(leaked)

    order = {ch: i for i, ch in enumerate((*CHANNELS, "any"))}
    summary = []
    for (doc_class, condition, channel), (judged, leaked) in sorted(
        table.items(), key=lambda kv: (kv[0][0] == "ALL", kv[0][0], kv[0][1] == "ALL",
                                       kv[0][1], order[kv[0][2]])  # fmt: skip
    ):
        summary.append({
            "document_class": doc_class,
            "condition": condition,
            "channel": channel,
            "fields_judged": judged,
            "leaked": leaked,
            "leak_pct": round(100 * leaked / judged, 2) if judged else 0.0,
        })  # fmt: skip
    return summary


def write_leaks_wide(results: list[FieldResult], path: Path) -> None:
    """One row per image x field, with one column per channel: leak, ok or n/a."""
    rows: dict[tuple[str, str, str], dict[str, Any]] = {}
    for r in results:
        row = rows.setdefault((r.image_id, r.field_type, r.field_name), {
            "image_id": r.image_id, "document_class": r.document_class, "variant": r.variant,
            "condition": r.condition, "field_type": r.field_type, "field_name": r.field_name,
            "box_source": r.box_source,
        })  # fmt: skip
        row[r.channel] = ("leak" if r.leaked else "ok") if r.judged else "n/a"
        if r.channel == "mosaic_blur":
            row["fill_style"] = r.rule
    path.parent.mkdir(parents=True, exist_ok=True)
    columns = ["image_id", "document_class", "variant", "condition", "field_type",
               "field_name", "box_source", *CHANNELS, "fill_style"]  # fmt: skip
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows.values())


def write_summary(summary: list[dict[str, Any]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(summary[0]) if summary else ["channel"])
        writer.writeheader()
        writer.writerows(summary)


def format_summary(summary: list[dict[str, Any]]) -> str:
    header = f"{'class':<14}{'condition':<16}{'channel':<16}{'judged':>8}{'leaked':>8}{'leak %':>9}"
    lines = [header, "-" * len(header)]
    for row in summary:
        lines.append(
            f"{row['document_class']:<14}{row['condition']:<16}{row['channel']:<16}"
            f"{row['fields_judged']:>8}{row['leaked']:>8}{row['leak_pct']:>8.2f}%"
        )
    return "\n".join(lines)
