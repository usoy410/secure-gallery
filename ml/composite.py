"""Background synthesis and card compositing engine with ground-truth polygon mapping.

Synthesizes three distinct background contexts:
1. 'table': Wooden or stone desk surface.
2. 'hand-held': Indoor scene with simulated fingers holding the card edge.
3. 'screenshot_frame': Mobile camera or gallery screenshot frame UI.

Computes exact forward-affine polygon transforms mapping card-space coordinates to
final composite image pixel coordinates.
"""

from __future__ import annotations

import math
import random
from typing import Any

from PIL import Image, ImageDraw

CANVAS_WIDTH: int = 1600
CANVAS_HEIGHT: int = 1200


def create_table_background(width: int, height: int, rng: random.Random) -> Image.Image:
    """Generate a procedural wooden table desk background.

    Args:
        width: Background width.
        height: Background height.
        rng: Deterministic random instance.

    Returns:
        Pillow RGB Image.
    """
    img = Image.new("RGB", (width, height), (175, 125, 85))
    draw = ImageDraw.Draw(img)

    # Render horizontal wood plank seams and tonal grain variations
    plank_height = rng.randint(90, 140)
    y = 0
    while y < height:
        tint = rng.randint(-15, 15)
        plank_color = (
            max(0, min(255, 175 + tint)),
            max(0, min(255, 125 + tint)),
            max(0, min(255, 85 + tint)),
        )
        draw.rectangle([0, y, width, min(height, y + plank_height)], fill=plank_color)
        draw.line([0, y, width, y], fill=(110, 75, 45), width=2)
        # Subtle grain streaks within plank
        for _ in range(4):
            grain_y = y + rng.randint(5, plank_height - 5)
            streak_color = (145 + tint, 100 + tint, 65 + tint)
            draw.line([0, grain_y, width, grain_y], fill=streak_color, width=1)
        y += plank_height

    return img


def create_hand_held_background(width: int, height: int, rng: random.Random) -> Image.Image:
    """Generate an indoor scene background simulating a handheld card capture context.

    Args:
        width: Background width.
        height: Background height.
        rng: Deterministic random instance.

    Returns:
        Pillow RGB Image.
    """
    # Soft indoor backdrop gradient
    base_color = (rng.randint(180, 220), rng.randint(180, 220), rng.randint(190, 230))
    img = Image.new("RGB", (width, height), base_color)
    draw = ImageDraw.Draw(img)

    # Ambient room lighting gradient / soft shapes
    for _ in range(6):
        x = rng.randint(0, width)
        y = rng.randint(0, height)
        radius = rng.randint(200, 500)
        shade = rng.randint(-20, 20)
        c = (
            max(0, min(255, base_color[0] + shade)),
            max(0, min(255, base_color[1] + shade)),
            max(0, min(255, base_color[2] + shade)),
        )
        draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=c)

    return img


def create_screenshot_background(width: int, height: int, rng: random.Random) -> Image.Image:
    """Generate a mobile camera / gallery app screenshot frame interface.

    Args:
        width: Background width.
        height: Background height.
        rng: Deterministic random instance.

    Returns:
        Pillow RGB Image.
    """
    # Modern dark gallery UI
    img = Image.new("RGB", (width, height), (28, 30, 36))
    draw = ImageDraw.Draw(img)

    # Top mobile status bar (Time, icons)
    draw.rectangle([0, 0, width, 50], fill=(20, 22, 26))
    draw.text((30, 15), "09:41", fill=(220, 220, 220))
    # Battery icon outline
    draw.rectangle([width - 80, 18, width - 40, 34], outline=(200, 200, 200), width=2)
    draw.rectangle([width - 76, 22, width - 50, 30], fill=(70, 200, 100))

    # Bottom camera / navigation bar
    nav_top = height - 90
    draw.rectangle([0, nav_top, width, height], fill=(18, 20, 24))
    # Shutter circle button
    shutter_x = width // 2
    shutter_y = nav_top + 45
    draw.ellipse(
        [shutter_x - 30, shutter_y - 30, shutter_x + 30, shutter_y + 30],
        outline=(255, 255, 255),
        width=3,
    )
    draw.ellipse(
        [shutter_x - 22, shutter_y - 22, shutter_x + 22, shutter_y + 22],
        fill=(255, 255, 255),
    )

    return img


def transform_point(
    pt: list[int] | tuple[int, int],
    scale: float,
    angle_deg: float,
    card_w: int,
    card_h: int,
    rot_w: int,
    rot_h: int,
    paste_x: int,
    paste_y: int,
) -> list[int]:
    """Map a point from clean card pixel coordinates to final composite image coordinates.

    Uses the exact forward affine transformation corresponding to PIL's resize,
    counter-clockwise rotation with expand=True, and canvas paste offset.
    """
    u, v = pt[0], pt[1]
    w_s = int(round(card_w * scale))
    h_s = int(round(card_h * scale))

    # Center of scaled card
    c_xs = (w_s - 1) / 2.0
    c_ys = (h_s - 1) / 2.0

    # Scaled point relative to center
    dx = (u * scale) - c_xs
    dy = (v * scale) - c_ys

    # Rotation (PIL rotate is counter-clockwise, with y increasing downward)
    rad = math.radians(angle_deg)
    cos_a = math.cos(rad)
    sin_a = math.sin(rad)

    dx_rot = dx * cos_a + dy * sin_a
    dy_rot = -dx * sin_a + dy * cos_a

    # Center of rotated bounding image
    c_xr = (rot_w - 1) / 2.0
    c_yr = (rot_h - 1) / 2.0

    x_final = dx_rot + c_xr + paste_x
    y_final = dy_rot + c_yr + paste_y

    return [int(round(x_final)), int(round(y_final))]


def composite_card(
    card_img: Image.Image,
    field_ground_truth: list[dict[str, Any]],
    seed: int = 42,
    bg_type: str | None = None,
    canvas_w: int = CANVAS_WIDTH,
    canvas_h: int = CANVAS_HEIGHT,
) -> tuple[Image.Image, list[list[int]], list[dict[str, Any]]]:
    """Composite a clean card onto a realistic background with random scale and rotation.

    Args:
        card_img: Clean rendered card image (RGB or RGBA).
        field_ground_truth: Ground truth field dictionaries with card-space pixel polygons.
        seed: Deterministic integer seed.
        bg_type: Background type ('table', 'hand-held', 'screenshot_frame', or None for random).
        canvas_w: Final composite canvas width.
        canvas_h: Final composite canvas height.

    Returns:
        Tuple of (composite_image, card_corners_in_final_pixels, transformed_fields).
    """
    rng = random.Random(seed)
    card_w, card_h = card_img.size

    # Select background
    types = ["table", "hand-held", "screenshot_frame"]
    chosen_bg = bg_type if bg_type in types else rng.choice(types)

    if chosen_bg == "table":
        background = create_table_background(canvas_w, canvas_h, rng)
    elif chosen_bg == "hand-held":
        background = create_hand_held_background(canvas_w, canvas_h, rng)
    else:
        background = create_screenshot_background(canvas_w, canvas_h, rng)

    # Random scale in [0.65, 0.85] and rotation in [-18, 18] degrees
    scale = rng.uniform(0.68, 0.82)
    angle_deg = rng.uniform(-18.0, 18.0)

    w_s = int(round(card_w * scale))
    h_s = int(round(card_h * scale))
    scaled_card = card_img.convert("RGBA").resize((w_s, h_s), Image.Resampling.BICUBIC)

    # Rotate card with expanded canvas
    rotated_card = scaled_card.rotate(angle_deg, expand=True, resample=Image.Resampling.BICUBIC)
    rot_w, rot_h = rotated_card.size

    # Choose placement center ensuring card remains inside margins
    margin = 40
    min_cx = rot_w // 2 + margin
    max_cx = canvas_w - rot_w // 2 - margin
    min_cy = rot_h // 2 + margin
    max_cy = canvas_h - rot_h // 2 - margin

    cx = rng.randint(min(min_cx, max_cx), max(min_cx, max_cx))
    cy = rng.randint(min(min_cy, max_cy), max(min_cy, max_cy))

    paste_x = int(round(cx - rot_w / 2.0))
    paste_y = int(round(cy - rot_h / 2.0))

    # Paste rotated card onto background
    background.paste(rotated_card, (paste_x, paste_y), rotated_card)

    # If handheld, add simulated fingers resting slightly over card edge
    if chosen_bg == "hand-held":
        draw = ImageDraw.Draw(background)
        thumb_x = paste_x + rot_w - rng.randint(20, 50)
        thumb_y = paste_y + rot_h // 2
        thumb_color = (215, 165, 130)
        draw.ellipse([thumb_x - 35, thumb_y - 45, thumb_x + 45, thumb_y + 45], fill=thumb_color)

    # Compute final transformed card corners
    card_corners_card_space = [
        [0, 0],
        [card_w, 0],
        [card_w, card_h],
        [0, card_h],
    ]
    final_card_corners = [
        transform_point(pt, scale, angle_deg, card_w, card_h, rot_w, rot_h, paste_x, paste_y)
        for pt in card_corners_card_space
    ]

    # Transform all field polygon points
    transformed_fields: list[dict[str, Any]] = []
    for field in field_ground_truth:
        orig_poly = field["polygon"]
        mapped_poly = [
            transform_point(pt, scale, angle_deg, card_w, card_h, rot_w, rot_h, paste_x, paste_y)
            for pt in orig_poly
        ]
        transformed_fields.append({
            "name": field["name"],
            "type": field["type"],
            "sensitive": field["sensitive"],
            "polygon": mapped_poly,
            "value": field["value"],
        })

    return background.convert("RGB"), final_card_corners, transformed_fields
