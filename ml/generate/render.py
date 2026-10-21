"""Draw one flat synthetic card from a template and a set of fictional values.

The designs are simplified reconstructions: plain gradients, generic shapes and
plain text. They copy no official artwork and carry no security features. Every
card carries a diagonal "SPECIMEN – NOT VALID" watermark.
"""

from __future__ import annotations

import math
import random
from typing import Any

from PIL import Image, ImageDraw, ImageFont

from ml.generate.values import IMAGE_FIELDS

CARD_WIDTH = 1012  # ID-1 width (85.6 mm) at about 300 dpi
WATERMARK_TEXT = "SPECIMEN – NOT VALID"
# The bundled font has no en dash glyph, so the dash is drawn as a bar between two words.
_WATERMARK_PARTS = ("SPECIMEN", "NOT VALID")

Color = tuple[int, int, int]
Box = tuple[int, int, int, int]

HEADINGS: dict[str, list[str]] = {
    "philsys-card": ["REPUBLIKA NG PILIPINAS", "Philippine Identification Card"],
    "philsys-ephilid": ["REPUBLIKA NG PILIPINAS", "ePhilID  Philippine Identification"],
    "lto-dl-current": ["REPUBLIC OF THE PHILIPPINES", "LAND TRANSPORTATION OFFICE",
                       "DRIVER'S LICENSE"],
    "lto-dl-previous": ["REPUBLIC OF THE PHILIPPINES", "LAND TRANSPORTATION OFFICE",
                        "NON-PROFESSIONAL DRIVER'S LICENSE"],
}  # fmt: skip


def font(size: int) -> ImageFont.FreeTypeFont:
    """Pillow's bundled sans-serif font, so output does not depend on system fonts."""
    return ImageFont.load_default(size)  # type: ignore[return-value]


def card_size(template: dict[str, Any]) -> tuple[int, int]:
    return CARD_WIDTH, round(CARD_WIDTH / template["aspect_ratio"])


def field_box(field: dict[str, Any], size: tuple[int, int]) -> Box:
    """Pixel bounding box of a field's normalized polygon."""
    w, h = size
    xs = [p[0] * w for p in field["polygon"]]
    ys = [p[1] * h for p in field["polygon"]]
    return round(min(xs)), round(min(ys)), round(max(xs)), round(max(ys))


def _gradient(size: tuple[int, int], top: Color, bottom: Color) -> Image.Image:
    w, h = size
    column = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        mix = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom, strict=True))
        column.putpixel((0, y), mix)
    return column.resize((w, h))


def _jitter(rng: random.Random, color: Color, amount: int = 12) -> Color:
    return tuple(max(0, min(255, c + rng.randint(-amount, amount))) for c in color)  # type: ignore[return-value]


def _wrap(text: str, fnt: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines: list[str] = []
    for paragraph in text.split("\n"):
        line = ""
        for word in paragraph.split():
            candidate = f"{line} {word}".strip()
            if line and fnt.getlength(candidate) > width:
                lines.append(line)
                line = word
            else:
                line = candidate
        lines.append(line)
    return lines


def _fit_text(text: str, box: Box, max_size: int, wrap: bool) -> tuple[int, list[str]]:
    """Largest font size (and its lines) whose text block fits inside the box."""
    x0, y0, x1, y1 = box
    width, height = x1 - x0, y1 - y0
    for size in range(max_size, 7, -1):
        fnt = font(size)
        lines = _wrap(text, fnt, width) if wrap else text.split("\n")
        line_h = round(size * 1.2)
        if len(lines) * line_h <= height and all(fnt.getlength(s) <= width for s in lines):
            return size, lines
    raise ValueError(f"Text does not fit in field box {box}: {text!r}")


def draw_text_field(draw: ImageDraw.ImageDraw, text: str, box: Box, color: Color) -> None:
    """Draw left-aligned text, vertically centered, inside the box with a small margin."""
    x0, y0, x1, y1 = box
    pad = max(2, round((y1 - y0) * 0.08))
    inner = (x0 + pad, y0 + pad, x1 - pad, y1 - pad)
    size, lines = _fit_text(text, inner, max_size=44, wrap=True)
    line_h = round(size * 1.2)
    y = inner[1] + (inner[3] - inner[1] - line_h * len(lines)) // 2
    fnt = font(size)
    for line in lines:
        draw.text((inner[0], y + (line_h - size) // 2), line, font=fnt, fill=color)
        y += line_h


def draw_avatar(img: Image.Image, box: Box, rng: random.Random) -> None:
    """Simple geometric placeholder portrait. Never a photo of a real person."""
    w, h = box[2] - box[0], box[3] - box[1]
    x0, y0, x1, y1 = 0, 0, w, h
    portrait = Image.new("RGB", (w, h), _jitter(rng, (214, 224, 236), 20))
    draw = ImageDraw.Draw(portrait)
    skin = rng.choice([(241, 206, 170), (224, 172, 128), (198, 140, 96), (150, 100, 66)])
    hair = rng.choice([(30, 24, 20), (70, 45, 30), (110, 80, 50), (40, 40, 45)])
    shirt = _jitter(rng, rng.choice([(40, 70, 130), (230, 230, 230), (120, 30, 40)]), 25)
    cx = x0 + w / 2
    draw.ellipse((x0 + w * 0.05, y0 + h * 0.70, x1 - w * 0.05, y1 + h * 0.35), fill=shirt)
    draw.rectangle((cx - w * 0.1, y0 + h * 0.55, cx + w * 0.1, y0 + h * 0.75), fill=skin)
    head = (cx - w * 0.27, y0 + h * 0.18, cx + w * 0.27, y0 + h * 0.64)
    draw.ellipse((head[0] - w * 0.03, head[1] - h * 0.05, head[2] + w * 0.03, head[3] - h * 0.18),
                 fill=hair)  # fmt: skip
    draw.ellipse(head, fill=skin)
    eye_y = y0 + h * 0.38
    for ex in (cx - w * 0.1, cx + w * 0.1):
        draw.ellipse((ex - w * 0.03, eye_y - h * 0.015, ex + w * 0.03, eye_y + h * 0.015),
                     fill=(30, 30, 30))  # fmt: skip
    draw.arc((cx - w * 0.1, y0 + h * 0.44, cx + w * 0.1, y0 + h * 0.54), 20, 160,
             fill=(120, 50, 50), width=max(2, round(w * 0.015)))  # fmt: skip
    img.paste(portrait, box[:2])


def draw_signature(draw: ImageDraw.ImageDraw, box: Box, rng: random.Random) -> None:
    """Random smooth scribble standing in for a handwritten signature."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    phase = [rng.uniform(0, math.tau) for _ in range(3)]
    freq = [rng.uniform(2, 5), rng.uniform(5, 9), rng.uniform(9, 14)]
    points = []
    for i in range(120):
        t = i / 119
        waves = enumerate(zip(freq, phase, strict=True))
        y = sum(math.sin(f * math.tau * t + p) / (k + 1) for k, (f, p) in waves)
        points.append((x0 + w * (0.08 + 0.84 * t), y0 + h * (0.5 + 0.22 * y)))
    draw.line(points, fill=(20, 30, 90), width=max(2, round(h * 0.04)), joint="curve")


def draw_qr(draw: ImageDraw.ImageDraw, box: Box, rng: random.Random) -> None:
    """QR-like module grid with finder patterns. It encodes nothing."""
    x0, y0, x1, y1 = box
    n = 25
    side = min(x1 - x0, y1 - y0)
    module = side / (n + 2)
    ox = x0 + (x1 - x0 - side) / 2 + module
    oy = y0 + (y1 - y0 - side) / 2 + module
    draw.rectangle((ox - module, oy - module, ox + side - module, oy + side - module), fill="white")
    finders = [(0, 0), (n - 7, 0), (0, n - 7)]

    def in_finder(r: int, c: int) -> bool:
        return any(fr - 1 <= r <= fr + 7 and fc - 1 <= c <= fc + 7 for fr, fc in finders)

    for r in range(n):
        for c in range(n):
            if in_finder(r, c):
                fr, fc = next((fr, fc) for fr, fc in finders if fr - 1 <= r <= fr + 7
                              and fc - 1 <= c <= fc + 7)  # fmt: skip
                dr, dc = r - fr, c - fc
                ring = max(abs(dr - 3), abs(dc - 3))
                dark = ring in (0, 1, 3) and 0 <= dr <= 6 and 0 <= dc <= 6
            else:
                dark = rng.random() < 0.5
            if dark:
                x, y = ox + c * module, oy + r * module
                draw.rectangle((x, y, x + module - 1, y + module - 1), fill="black")


def draw_headings(draw: ImageDraw.ImageDraw, lines: list[str], box: Box, color: Color) -> None:
    x0, y0, x1, y1 = box
    emblem = y1 - y0
    draw.ellipse((x0, y0, x0 + emblem, y1), outline=color, width=3)
    draw.ellipse((x0 + emblem * 0.3, y0 + emblem * 0.3, x0 + emblem * 0.7, y0 + emblem * 0.7),
                 fill=color)  # fmt: skip
    text_box = (x0 + round(emblem * 1.3), y0, x1, y1)
    size, fitted = _fit_text("\n".join(lines), text_box, max_size=30, wrap=False)
    fnt = font(size)
    line_h = round(size * 1.2)
    y = y0 + (y1 - y0 - line_h * len(fitted)) // 2
    for line in fitted:
        draw.text((text_box[0], y), line, font=fnt, fill=color)
        y += line_h


def _draw_logo(draw: ImageDraw.ImageDraw, text: str, box: Box, color: Color) -> None:
    x0, y0, x1, y1 = box
    draw.rounded_rectangle(box, radius=(y1 - y0) // 4, outline=color, width=3)
    size, lines = _fit_text(text, (x0 + 10, y0 + 6, x1 - 10, y1 - 6), max_size=34, wrap=True)
    fnt = font(size)
    line_h = round(size * 1.2)
    y = y0 + (y1 - y0 - line_h * len(lines)) // 2
    for line in lines:
        draw.text(((x0 + x1 - fnt.getlength(line)) / 2, y), line, font=fnt, fill=color)
        y += line_h


def _background(template: dict[str, Any], size: tuple[int, int], rng: random.Random) -> Image.Image:
    variant = template["id"]
    if variant == "philsys-ephilid":
        img = Image.new("RGB", size, _jitter(rng, (248, 248, 244), 4))
        border = (4, 4, size[0] - 5, size[1] - 5)
        ImageDraw.Draw(img).rectangle(border, outline=(150, 150, 150), width=3)
        return img
    if template["document_class"] == "philsys":
        img = _gradient(size, _jitter(rng, (214, 228, 242)), _jitter(rng, (246, 246, 240)))
    elif template["document_class"] == "lto":
        img = _gradient(size, _jitter(rng, (226, 238, 214)), _jitter(rng, (250, 240, 220)))
    else:
        hue = rng.choice([(20, 40, 90), (60, 20, 80), (10, 70, 60), (40, 40, 45)])
        img = _gradient(size, _jitter(rng, hue, 15), _jitter(rng, tuple(c // 2 for c in hue), 10))
    draw = ImageDraw.Draw(img, "RGBA")
    w, h = size
    for k in range(6):
        amp, phase = rng.uniform(0.02, 0.06) * h, rng.uniform(0, math.tau)
        base = h * (0.2 + 0.13 * k)
        pts = [(x, base + amp * math.sin(x / w * math.tau * 1.5 + phase))
               for x in range(0, w + 1, 8)]  # fmt: skip
        draw.line(pts, fill=(255, 255, 255, 40), width=2)
    return img


def _draw_card_extras(img: Image.Image, template: dict[str, Any], rng: random.Random) -> None:
    """Non-field decoration: chip on card fronts; stripe and signature panel on backs."""
    draw = ImageDraw.Draw(img)
    w, h = img.size
    if template["id"] == "payment-card-front":
        draw.rounded_rectangle((w * 0.08, h * 0.29, w * 0.19, h * 0.46), radius=10,
                               fill=(212, 178, 92), outline=(150, 120, 60), width=2)  # fmt: skip
    if template["id"] == "payment-card-back":
        draw.rectangle((0, h * 0.08, w, h * 0.26), fill=(15, 15, 15))
        draw.rectangle((w * 0.06, h * 0.46, w * 0.66, h * 0.58), fill=(238, 238, 230))
        for k in range(12):
            x = w * 0.06 + k * w * 0.05
            draw.line((x, h * 0.46, x + w * 0.04, h * 0.58), fill=(205, 205, 195), width=3)


def watermark(img: Image.Image) -> Image.Image:
    """Overlay the diagonal "SPECIMEN – NOT VALID" watermark across the whole card."""
    w, h = img.size
    angle = math.degrees(math.atan2(h, w))
    diagonal = math.hypot(w, h)
    left, right = _WATERMARK_PARTS

    def width(size: int) -> float:
        f = font(size)
        return f.getlength(left) + f.getlength(right) + size * 1.6

    size = 20
    while width(size + 2) < diagonal * 0.8:
        size += 2
    fnt = font(size)
    fill = (200, 30, 30, 70)
    layer = Image.new("RGBA", (round(width(size)) + 20, size * 2), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.text((10, size // 2), left, font=fnt, fill=fill)
    dash_x = 10 + fnt.getlength(left) + size * 0.3
    dash_y = size // 2 + size * 0.62
    draw.rectangle((dash_x, dash_y - size * 0.05, dash_x + size, dash_y + size * 0.05), fill=fill)
    draw.text((dash_x + size * 1.3, size // 2), right, font=fnt, fill=fill)
    layer = layer.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
    out = img.convert("RGBA")
    out.alpha_composite(layer, ((w - layer.width) // 2, (h - layer.height) // 2))
    return out


def render_card(
    template: dict[str, Any], values: dict[str, str], rng: random.Random
) -> Image.Image:
    """Render the flat card as RGBA with rounded ID-1 corners."""
    size = card_size(template)
    img = _background(template, size, rng)
    _draw_card_extras(img, template, rng)
    draw = ImageDraw.Draw(img)
    dark_card = template["document_class"] == "payment_card"
    text_color: Color = (242, 242, 242) if dark_card else (20, 20, 24)

    for field in template["fields"]:
        box = field_box(field, size)
        kind = field["type"]
        if kind == "face_photo":
            draw_avatar(img, box, rng)
        elif kind == "signature":
            if template["id"] != "philsys-ephilid":
                draw.rectangle(box, fill=_jitter(rng, (245, 245, 240), 5))
            draw_signature(draw, box, rng)
        elif kind == "qr_code":
            draw_qr(draw, box, rng)
        elif kind == "headings":
            draw_headings(draw, HEADINGS[template["id"]], box, (20, 40, 90))
        elif kind == "bank_logo":
            label = "SPECIMEN BANK" if template["id"] == "payment-card-front" else (
                "SPECIMEN BANK  CUSTOMER SERVICE 0000 000 0000")
            _draw_logo(draw, label, box, text_color)
        elif kind == "card_network_logo":
            _draw_logo(draw, "PAYNET", box, text_color)
        elif kind == "cvv":
            draw.rectangle(box, fill=(255, 255, 255))
            draw_text_field(draw, values[kind], box, (10, 10, 10))
        elif kind not in IMAGE_FIELDS:
            draw_text_field(draw, values[kind], box, text_color)

    img = watermark(img)
    mask = Image.new("L", size, 0)
    corner_radius = round(size[0] * 3.18 / 85.6)  # ID-1 corner radius is 3.18 mm
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size[0] - 1, size[1] - 1), radius=corner_radius, fill=255
    )
    img.putalpha(mask)
    return img
