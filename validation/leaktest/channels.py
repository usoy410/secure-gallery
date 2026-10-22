"""Leak channels for boxes that look fine but still leak (SECURE_GALLERY.md §9.5, rows 2-4).

All three judge the redaction boxes a tool drew over a field. Boxes come from the tool's
box file when one exists (validation.localize writes <id>.boxes.json) or from the label
(D7 failed-redaction cases). Without either, the field polygon stands in for the box and
results are marked box_source="field".

- partial_chars: OCR on a band 15% of the box's size around each box, with the box
  itself masked out. Clipped tops or bottoms of characters that still read back by the
  §9.2 rule are a leak.
- non_opaque_fill: levels, contrast and gamma stretches inside the box, then OCR. Text
  recovered by the §9.2 rule is a leak (a dark but see-through fill, for example).
- mosaic_blur: classify the fill style as solid, mosaic, blur or other from block and
  edge statistics. Mosaic or blur over text is recorded as a leak risk. Recovery attacks
  exist (Hill et al., 2016) but are cited, not reimplemented here.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps

BAND = 0.15  # band width as a share of the box's own width and height
OVERLAP_MIN = 0.05  # a box counts for a field when it covers this share of the field
MOSAIC_MIN_R2 = 0.84  # block means explain this much of the variance in a mosaic
BLUR_MAX_EDGE = 0.45  # strongest edges in a blur stay below this share of the range
FLAT_STD = 3.0  # below this the fill has no structure to classify

Polygon = list[tuple[float, float]]


@dataclass(frozen=True)
class ChannelOutcome:
    judged: bool
    leaked: bool
    score: float
    rule: str


def as_polygon(box: list) -> Polygon:
    """A box as polygon points: [x0, y0, x1, y1] rectangles or [[x, y], ...] lists."""
    if len(box) == 4 and all(isinstance(v, int | float) for v in box):
        x0, y0, x1, y1 = box
        return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
    return [(float(p[0]), float(p[1])) for p in box]


def _mask(size: tuple[int, int], polygons: list[Polygon]) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    for poly in polygons:
        draw.polygon(poly, fill=255)
    return mask


def _area(mask: Image.Image) -> int:
    return mask.histogram()[255]


def boxes_over_field(
    size: tuple[int, int], field_polygon: list, boxes: list[Polygon]
) -> list[Polygon]:
    """The boxes that cover at least OVERLAP_MIN of the field."""
    field = _mask(size, [as_polygon(field_polygon)])
    field_area = max(_area(field), 1)
    return [
        b for b in boxes
        if _area(ImageChops.multiply(field, _mask(size, [b]))) / field_area >= OVERLAP_MIN
    ]  # fmt: skip


def _scaled(poly: Polygon, factor: float) -> Polygon:
    cx = sum(p[0] for p in poly) / len(poly)
    cy = sum(p[1] for p in poly) / len(poly)
    return [(cx + (x - cx) * factor, cy + (y - cy) * factor) for x, y in poly]


def band_crop(image: Image.Image, boxes: list[Polygon]) -> Image.Image | None:
    """Pixels in a BAND-wide ring around the boxes; the boxes and everything else white."""
    rgb = image.convert("RGB")
    outer = _mask(rgb.size, [_scaled(b, 1 + 2 * BAND) for b in boxes])
    band = ImageChops.subtract(outer, _mask(rgb.size, boxes))
    region = band.getbbox()
    if region is None:
        return None
    out = Image.new("RGB", (region[2] - region[0], region[3] - region[1]), (255, 255, 255))
    out.paste(rgb.crop(region), (0, 0), band.crop(region))
    return out


def box_crop(image: Image.Image, box: Polygon) -> Image.Image | None:
    """Bounding-box crop of one box, with pixels outside the box set to the box's median.

    The box's 1 px outline is dropped: polygon fills include their edge pixels, which
    would otherwise pull in pixels from just outside the fill.
    """
    rgb = image.convert("RGB")
    mask = _mask(rgb.size, [box])
    inner = mask.filter(ImageFilter.MinFilter(3))
    if inner.getbbox() is not None:
        mask = inner
    region = mask.getbbox()
    if region is None:
        return None
    crop = rgb.crop(region)
    inside = mask.crop(region)
    pixels = np.asarray(crop).reshape(-1, 3)[np.asarray(inside).reshape(-1) > 0]
    median = tuple(int(v) for v in np.median(pixels, axis=0)) if len(pixels) else (0, 0, 0)
    out = Image.new("RGB", crop.size, median)
    out.paste(crop, (0, 0), inside)
    return out


def stretches(crop: Image.Image) -> list[Image.Image]:
    """Levels, contrast and gamma stretches that pull faint text out of a dark or light fill."""
    gray = ImageOps.grayscale(crop)
    levels = ImageOps.autocontrast(gray, cutoff=1)
    dark_gamma = levels.point(lambda v: round(255 * (v / 255) ** 0.4))
    return [levels, dark_gamma, ImageOps.equalize(gray)]


# --- fill style -------------------------------------------------------------------


def _best_grid(g: np.ndarray, axis: int) -> tuple[int, int]:
    """Block size and offset whose grid lines carry the most edge energy along one axis."""
    d = np.abs(np.diff(g, axis=axis))
    d = np.where(d > 4, d, 0)
    profile = d.sum(axis=0 if axis == 1 else 1)
    total = profile.sum()
    if total == 0:
        return 0, 0
    best = (0, 0, 0.0)
    for size in range(3, min(41, len(profile))):
        for offset in range(size):
            share = profile[offset::size].sum() / total
            if share > best[2] + 1e-9:
                best = (size, offset, share)
    return best[0], (best[1] + 1) % best[0]


def _block_r2(g: np.ndarray) -> float:
    """Share of the variance explained by the means of the best-fitting grid of blocks."""
    bx, ox = _best_grid(g, 1)
    by, oy = _best_grid(g, 0)
    if not bx or not by:
        return 0.0
    xs = [0, *range(ox, g.shape[1], bx), g.shape[1]]
    ys = [0, *range(oy, g.shape[0], by), g.shape[0]]
    sse = 0.0
    for y0, y1 in zip(ys, ys[1:], strict=False):
        for x0, x1 in zip(xs, xs[1:], strict=False):
            block = g[y0:y1, x0:x1]
            if block.size:
                sse += float(((block - block.mean()) ** 2).sum())
    return 1.0 - sse / g.size / max(float(g.var()), 1e-6)


def fill_style(crop: Image.Image) -> tuple[str, float]:
    """("solid" | "mosaic" | "blur" | "other", the statistic that decided it)."""
    g = np.asarray(ImageOps.grayscale(crop), dtype=np.float32)
    if g.size == 0 or g.std() < FLAT_STD:
        return "solid", round(float(g.std()) if g.size else 0.0, 3)
    steps = np.concatenate([np.abs(np.diff(g, axis=1)).ravel(), np.abs(np.diff(g, axis=0)).ravel()])
    low, high = np.percentile(g, [0.5, 99.5])
    edge = float(np.percentile(steps, 99.9)) / max(float(high - low), 1.0)
    if edge < BLUR_MAX_EDGE:
        return "blur", round(edge, 3)
    r2 = _block_r2(g)
    if r2 >= MOSAIC_MIN_R2:
        return "mosaic", round(r2, 3)
    return "other", round(r2, 3)


# --- channels -----------------------------------------------------------------------

Judge = Callable[[str], tuple[bool, float, str]]  # OCR text -> (leaked, CER, rule)


def partial_chars(image: Image.Image, boxes: list[Polygon], read, judge: Judge) -> ChannelOutcome:
    band = band_crop(image, boxes)
    if band is None:
        return ChannelOutcome(True, False, 1.0, "empty_band")
    leaked, rate, rule = judge(read(band))
    return ChannelOutcome(True, leaked, rate, rule)


def non_opaque_fill(
    image: Image.Image, boxes: list[Polygon], read, judge: Judge
) -> ChannelOutcome:
    texts = []
    for box in boxes:
        crop = box_crop(image, box)
        if crop is None:
            continue
        extrema = ImageOps.grayscale(crop).getextrema()
        if extrema[0] == extrema[1]:
            continue  # perfectly flat: no stretch can reveal anything
        texts += [read(s.convert("RGB")) for s in stretches(crop)]
    if not texts:
        return ChannelOutcome(True, False, 1.0, "flat")
    outcomes = [judge(t) for t in texts]
    leaked = [o for o in outcomes if o[0]]
    best = min(leaked or outcomes, key=lambda o: o[1])
    return ChannelOutcome(True, bool(leaked), best[1], best[2])


def mosaic_blur(image: Image.Image, boxes: list[Polygon]) -> ChannelOutcome:
    styles = [fill_style(c) for c in (box_crop(image, b) for b in boxes) if c is not None]
    if not styles:
        return ChannelOutcome(False, False, 0.0, "no_box")
    risky = [s for s in styles if s[0] in ("mosaic", "blur")]
    style, stat = (risky or styles)[0]
    return ChannelOutcome(True, bool(risky), stat, style)
