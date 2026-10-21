"""Place a flat card on a procedural background and map its geometry into the scene.

Backgrounds are drawn from shapes and noise, never from photos. The card goes
through one affine transform (scale, rotation, translation). The same matrix
maps every template polygon, so labels give exact pixel polygons in the output.
"""

from __future__ import annotations

import math
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from ml.generate.render import font

BACKGROUNDS = ("table", "handheld", "screenshot")

Point = tuple[float, float]


def _noise(rng: random.Random, size: tuple[int, int], scale: int) -> np.ndarray:
    """Smooth value noise in [0, 1], upsampled from a coarse random grid."""
    w, h = size
    coarse = np.random.default_rng(rng.getrandbits(32)).random((h // scale + 2, w // scale + 2))
    img = Image.fromarray((coarse * 255).astype(np.uint8)).resize(
        (w, h), Image.Resampling.BICUBIC
    )
    return np.asarray(img, dtype=np.float32) / 255.0


def _table(rng: random.Random, size: tuple[int, int]) -> Image.Image:
    w, h = size
    base = np.array(rng.choice([(150, 105, 70), (185, 150, 110), (90, 70, 55), (200, 200, 195)]))
    grain_dir = rng.uniform(0, math.pi)
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    along = xs * math.cos(grain_dir) + ys * math.sin(grain_dir)
    grain = np.sin(along / rng.uniform(6, 14) + 4 * _noise(rng, size, 64)) * 0.5 + 0.5
    shade = 0.8 + 0.25 * grain[..., None] + 0.1 * _noise(rng, size, 16)[..., None]
    return Image.fromarray(np.clip(base * shade, 0, 255).astype(np.uint8), "RGB")


def _room(rng: random.Random, size: tuple[int, int]) -> Image.Image:
    img = Image.new("RGB", size, tuple(rng.randint(90, 200) for _ in range(3)))
    draw = ImageDraw.Draw(img)
    w, h = size
    for _ in range(8):
        cx, cy = rng.uniform(0, w), rng.uniform(0, h)
        r = rng.uniform(0.1, 0.4) * max(w, h)
        color = tuple(rng.randint(40, 230) for _ in range(3))
        draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=color)
    return img.filter(ImageFilter.GaussianBlur(max(w, h) / 25))


def _screenshot(
    rng: random.Random, size: tuple[int, int]
) -> tuple[Image.Image, tuple[int, int, int, int]]:
    """Phone screenshot chrome. Returns the image and the content area box."""
    w, h = size
    dark = rng.random() < 0.4
    bg = (18, 18, 20) if dark else (250, 250, 250)
    fg = (235, 235, 235) if dark else (25, 25, 25)
    img = Image.new("RGB", size, bg)
    draw = ImageDraw.Draw(img)
    bar = round(h * 0.035)
    small = font(round(bar * 0.6))
    clock = f"{rng.randint(1, 12)}:{rng.randint(0, 59):02d}"
    draw.text((w * 0.05, bar * 0.2), clock, font=small, fill=fg)
    draw.rectangle((w * 0.88, bar * 0.3, w * 0.94, bar * 0.7), outline=fg, width=2)
    app_bar = round(h * 0.07)
    draw.rectangle((0, bar, w, bar + app_bar), fill=(40, 40, 46) if dark else (235, 235, 240))
    title = rng.choice(["Photos", "Messages", "Chat", "Downloads"])
    draw.text((w * 0.15, bar + app_bar * 0.25), title, font=font(round(app_bar * 0.45)), fill=fg)
    nav = round(h * 0.06)
    draw.rectangle((0, h - nav, w, h), fill=(40, 40, 46) if dark else (235, 235, 240))
    for k in range(3):
        cx = w * (0.25 + 0.25 * k)
        button = (cx - nav * 0.2, h - nav * 0.7, cx + nav * 0.2, h - nav * 0.3)
        draw.ellipse(button, outline=fg, width=2)
    return img, (0, bar + app_bar, w, h - nav)


def _hand_layer(card_w: int, card_h: int, pad: int, rng: random.Random) -> Image.Image:
    """Palm and fingers drawn in card coordinates, so they move with the card."""
    layer = Image.new("RGBA", (card_w + 2 * pad, card_h + 2 * pad), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    skin = rng.choice([(241, 206, 170), (224, 172, 128), (198, 140, 96), (150, 100, 66)]) + (255,)
    # Palm under the lower half; fingers peek out past the left edge.
    palm = (pad - card_w * 0.15, pad + card_h * 0.45, pad + card_w * 0.55, pad + card_h * 1.6)
    draw.ellipse(palm, fill=skin)
    for k in range(4):
        y = pad + card_h * (0.15 + 0.17 * k)
        draw.rounded_rectangle((pad - card_w * 0.12, y, pad + card_w * 0.2, y + card_h * 0.13),
                               radius=round(card_h * 0.06), fill=skin)  # fmt: skip
    return layer


def _matrix(scale: float, angle_deg: float, center: Point, card_center: Point) -> np.ndarray:
    """3x3 affine: card pixels -> scene pixels."""
    a = math.radians(angle_deg)
    cos, sin = math.cos(a) * scale, math.sin(a) * scale
    cx, cy = card_center
    tx, ty = center
    return np.array([[cos, -sin, tx - cos * cx + sin * cy],
                     [sin, cos, ty - sin * cx - cos * cy],
                     [0.0, 0.0, 1.0]])  # fmt: skip


def apply(m: np.ndarray, points: list[Point]) -> list[Point]:
    pts = np.array([[x, y, 1.0] for x, y in points]).T
    out = m @ pts
    return [(float(x), float(y)) for x, y in zip(out[0], out[1], strict=True)]


def _warp(layer: Image.Image, m: np.ndarray, size: tuple[int, int]) -> Image.Image:
    inv = np.linalg.inv(m)
    coeffs = (inv[0, 0], inv[0, 1], inv[0, 2], inv[1, 0], inv[1, 1], inv[1, 2])
    return layer.transform(size, Image.Transform.AFFINE, coeffs, resample=Image.Resampling.BICUBIC)


def compose(card: Image.Image, rng: random.Random) -> tuple[Image.Image, np.ndarray, str]:
    """Composite the card onto a random background.

    Returns the RGB scene, the card-to-scene matrix, and the background kind.
    """
    kind = rng.choice(BACKGROUNDS)
    cw, ch = card.size
    diag = math.hypot(cw, ch)

    if kind == "screenshot":
        size = rng.choice([(720, 1280), (1080, 1920)])
        scene, (ax0, ay0, ax1, ay1) = _screenshot(rng, size)
        angle = 0.0
        width = rng.uniform(0.7, 0.95) * (ax1 - ax0)
        scale = width / cw
        half_w, half_h = cw * scale / 2, ch * scale / 2
    else:
        size = rng.choice([(1280, 960), (960, 1280), (1600, 1200)])
        scene = _table(rng, size) if kind == "table" else _room(rng, size)
        ax0, ay0, ax1, ay1 = 0, 0, size[0], size[1]
        angle = rng.uniform(-30, 30) if kind == "table" else rng.uniform(-15, 15)
        scale = rng.uniform(0.4, 0.85) * min(size) / diag
        a = math.radians(angle)
        half_w = (abs(math.cos(a)) * cw + abs(math.sin(a)) * ch) * scale / 2
        half_h = (abs(math.sin(a)) * cw + abs(math.cos(a)) * ch) * scale / 2

    # Keep the whole card inside the content area (cropping belongs to the conditions issue).
    margin = 4
    cx = rng.uniform(ax0 + half_w + margin, ax1 - half_w - margin)
    cy = rng.uniform(ay0 + half_h + margin, ay1 - half_h - margin)
    m = _matrix(scale, angle, (cx, cy), (cw / 2, ch / 2))

    scene = scene.convert("RGBA")
    if kind == "handheld":
        pad = round(ch * 0.7)
        hand = _hand_layer(cw, ch, pad, rng)
        shift = np.array([[1, 0, -pad], [0, 1, -pad], [0, 0, 1]], dtype=float)
        scene.alpha_composite(_warp(hand, m @ shift, size))
    if kind == "table":
        shadow = Image.new("RGBA", card.size, (0, 0, 0, 0))
        shadow.putalpha(card.getchannel("A").point(lambda v: v * 90 // 255))
        offset = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1]], dtype=float)
        offset[:2, 2] = (8 / scale, 10 / scale)
        warped = _warp(shadow, m @ offset, size).filter(ImageFilter.GaussianBlur(6))
        scene.alpha_composite(warped)
    scene.alpha_composite(_warp(card, m, size))
    return scene.convert("RGB"), m, kind
