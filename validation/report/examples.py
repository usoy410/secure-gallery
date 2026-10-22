"""Example gallery for the RQ4 report: a few test images side by side, per strategy.

Each example is one JPEG: the original synthetic card, then the redacted copy each
strategy saved, with the fields the leak test flagged outlined (red: text recovered,
orange: picture not fully covered). It reads what an RQ4 run already wrote
(rq4_fields.csv, summary.json and the saved redactions under <data>/rq4_work), so it
needs no OCR and runs in seconds.

Every image is synthetic (see data/benchmark/DATASHEET.md); the gallery never shows a
real ID or card. Tiles are small so the committed folder stays under MAX_TOTAL_BYTES.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from validation.localize.core import STRATEGIES

TILE_WIDTH = 360
CARD_MARGIN = 0.08  # share of the card's size kept around it when cropping a tile
CAPTION_HEIGHT = 34
JPEG_QUALITY = 80
MAX_FILE_BYTES = 150_000
MAX_TOTAL_BYTES = 1_500_000
DOCUMENT_CLASSES = ("philsys", "lto", "payment_card")
SEVERITY = 2  # middle severity: the conditions bite, the text is still mostly readable
TEXT_COLOR, PICTURE_COLOR = (220, 30, 30), (255, 150, 0)


def _read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def pick_examples(
    records: list[dict[str, str]], fields: list[dict[str, str]]
) -> list[dict[str, str]]:
    """One clean card per document class, then one image per condition at SEVERITY.

    For each condition the image where ocr_first leaked the most text fields wins
    (ties go to the lower image id), so the examples show where strategies differ.
    """
    scored = {r["image_id"] for r in fields}
    by_id = {Path(r["image_path"]).stem: r for r in records if Path(r["image_path"]).stem in scored}
    leaks: dict[str, int] = {}
    for row in fields:
        if row["strategy"] == "ocr_first" and row["kind"] == "text" and row["leaked"] == "True":
            leaks[row["image_id"]] = leaks.get(row["image_id"], 0) + 1

    picked = []
    for doc in DOCUMENT_CLASSES:
        clean = sorted(i for i, r in by_id.items()
                       if r["document_class"] == doc and r["condition"] == "none")  # fmt: skip
        picked += [by_id[clean[0]]] if clean else []
    conditions = sorted({r["condition"] for r in by_id.values()} - {"none"})
    for condition in conditions:
        ids = [i for i, r in by_id.items()
               if r["condition"] == condition and int(r["severity"]) == SEVERITY]  # fmt: skip
        if ids:
            picked.append(by_id[min(ids, key=lambda i: (-leaks.get(i, 0), i))])
    return picked


def card_crop(label: dict, size: tuple[int, int]) -> tuple[int, int, int, int]:
    """Bounding box of the card plus a margin, clipped to the image; whole image if unknown."""
    points = [p for p in label.get("card_corners") or [] if p]
    if not points:
        return 0, 0, size[0], size[1]
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    mx = (max(xs) - min(xs)) * CARD_MARGIN
    my = (max(ys) - min(ys)) * CARD_MARGIN
    return (max(0, round(min(xs) - mx)), max(0, round(min(ys) - my)),
            min(size[0], round(max(xs) + mx)), min(size[1], round(max(ys) + my)))  # fmt: skip


def _tile(image: Image.Image, caption: str, height: int, font) -> Image.Image:
    tile = Image.new("RGB", (TILE_WIDTH, height + CAPTION_HEIGHT), "white")
    tile.paste(image.resize((TILE_WIDTH, height), Image.Resampling.LANCZOS), (0, 0))
    draw = ImageDraw.Draw(tile)
    for i, line in enumerate(caption.split("\n")):
        draw.text((6, height + 2 + i * 15), line, fill="black", font=font)
    return tile


def build_example(
    record: dict[str, str],
    rows: list[dict[str, str]],
    data_dir: Path,
    pad: int,
) -> Image.Image:
    """Original plus one tile per strategy, leaked fields outlined."""
    label = json.loads((data_dir / record["label_path"]).read_text(encoding="utf-8"))
    polygons = {(f["type"], f.get("name", "")): f["polygon"] for f in label.get("fields", [])}
    image_id = Path(record["image_path"]).stem
    with Image.open(data_dir / record["image_path"]) as img:
        original = img.convert("RGB")
    crop = card_crop(label, original.size)
    height = round((crop[3] - crop[1]) * TILE_WIDTH / (crop[2] - crop[0]))
    line_width = max(2, (crop[2] - crop[0]) // 150)
    font = ImageFont.load_default(size=13)

    title = f"original: {record['document_class']}, {record['condition']}"
    if record["condition"] != "none":
        title += f" severity {record['severity']}"
    tiles = [_tile(original.crop(crop), f"{title}\n(synthetic)", height, font)]
    work = data_dir / "rq4_work" / record["split"]
    for strategy in STRATEGIES:
        saved = work / strategy / f"pad{pad}" / f"{image_id}.jpg"
        with Image.open(saved) as img:
            redacted = img.convert("RGB")
        draw = ImageDraw.Draw(redacted)
        mine = [r for r in rows if r["strategy"] == strategy]
        for row in mine:
            if row["leaked"] == "True":
                color = PICTURE_COLOR if row["kind"] == "picture" else TEXT_COLOR
                points = [tuple(p) for p in polygons[(row["field_type"], row["field_name"])]]
                draw.polygon(points, outline=color, width=line_width)
        text = [r for r in mine if r["kind"] == "text"]
        pictures = [r for r in mine if r["kind"] == "picture"]
        caption = (f"{strategy}: {sum(r['leaked'] == 'True' for r in text)}/{len(text)} "
                   f"text leaked")  # fmt: skip
        if pictures:
            covered = sorted(float(r["covered"]) for r in pictures)
            caption += f"\npictures: median {covered[len(covered) // 2]:.0%} covered"
        tiles.append(_tile(redacted.crop(crop), caption, height, font))

    sheet = Image.new("RGB", (TILE_WIDTH * len(tiles), tiles[0].height), "white")
    for i, tile in enumerate(tiles):
        sheet.paste(tile, (i * TILE_WIDTH, 0))
    return sheet


def write_examples(data_dir: Path, report_dir: Path) -> list[Path]:
    """Build the gallery into <report_dir>/examples from an earlier RQ4 run."""
    pad = json.loads((report_dir / "summary.json").read_text(encoding="utf-8"))["pad"]
    fields = _read_csv(report_dir / "rq4_fields.csv")
    records = _read_csv(data_dir / "manifest.csv")
    out = report_dir / "examples"
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.jpg"):
        old.unlink()

    written = []
    for n, record in enumerate(pick_examples(records, fields), start=1):
        image_id = Path(record["image_path"]).stem
        rows = [r for r in fields if r["image_id"] == image_id]
        sheet = build_example(record, rows, data_dir, pad)
        path = out / f"{n:02d}_{record['document_class']}_{record['condition']}.jpg"
        sheet.save(path, "JPEG", quality=JPEG_QUALITY, optimize=True)
        written.append(path)
    return written
