"""Procedural avatar, signature, and QR code generation for synthetic ID cards.

Strictly enforces ethical privacy rules:
- Faces are procedural geometric cartoon avatars. Real photos of people are NEVER used.
- Signatures are procedurally generated bezier/cursive vector strokes.
- QR codes are synthetically rendered using standard 2D barcode encoding.
"""

from __future__ import annotations

import random

import qrcode
from PIL import Image, ImageDraw

# Palette of stylized cartoon skin tones
SKIN_TONES: list[tuple[int, int, int]] = [
    (245, 215, 190),
    (235, 195, 160),
    (210, 165, 130),
    (185, 135, 100),
    (150, 105, 75),
]

# Palette of cartoon clothing colors
SHIRT_COLORS: list[tuple[int, int, int]] = [
    (40, 70, 120),
    (60, 110, 80),
    (120, 50, 50),
    (70, 70, 80),
    (180, 120, 40),
]

# Palette of hair colors
HAIR_COLORS: list[tuple[int, int, int]] = [
    (30, 25, 25),
    (60, 45, 35),
    (90, 65, 45),
    (45, 45, 50),
]


def generate_avatar(width: int, height: int, rng: random.Random) -> Image.Image:
    """Generate a stylized geometric cartoon avatar portrait.

    Why: SECURE_GALLERY.md §10.2 and Issue #5 strictly forbid real photographs.
    This creates a deterministic, recognizable synthetic portrait placeholder.

    Args:
        width: Image width in pixels.
        height: Image height in pixels.
        rng: Seeded random instance for deterministic generation.

    Returns:
        Pillow RGBA Image containing the rendered geometric portrait.
    """
    img = Image.new("RGBA", (width, height), (225, 230, 240, 255))
    draw = ImageDraw.Draw(img)

    skin = rng.choice(SKIN_TONES)
    shirt = rng.choice(SHIRT_COLORS)
    hair = rng.choice(HAIR_COLORS)

    # Torso / shoulders at bottom
    torso_top = int(height * 0.65)
    draw.ellipse(
        [-width * 0.2, torso_top, width * 1.2, height * 1.4],
        fill=shirt,
    )

    # Neck
    neck_w = int(width * 0.24)
    neck_x0 = (width - neck_w) // 2
    neck_y0 = int(height * 0.52)
    neck_y1 = torso_top + 10
    draw.rectangle([neck_x0, neck_y0, neck_x0 + neck_w, neck_y1], fill=skin)

    # Head oval
    head_w = int(width * 0.52)
    head_h = int(height * 0.44)
    head_x0 = (width - head_w) // 2
    head_y0 = int(height * 0.16)
    draw.ellipse([head_x0, head_y0, head_x0 + head_w, head_y0 + head_h], fill=skin)

    # Hair on top
    draw.chord(
        [head_x0 - 4, head_y0 - 6, head_x0 + head_w + 4, head_y0 + int(head_h * 0.7)],
        start=180,
        end=360,
        fill=hair,
    )

    # Eyes
    eye_y = head_y0 + int(head_h * 0.46)
    eye_r = max(2, int(width * 0.03))
    left_eye_x = head_x0 + int(head_w * 0.30)
    right_eye_x = head_x0 + int(head_w * 0.70)
    draw.ellipse(
        [left_eye_x - eye_r, eye_y - eye_r, left_eye_x + eye_r, eye_y + eye_r],
        fill=(30, 30, 30),
    )
    draw.ellipse(
        [right_eye_x - eye_r, eye_y - eye_r, right_eye_x + eye_r, eye_y + eye_r],
        fill=(30, 30, 30),
    )

    # Eyebrows
    brow_y = eye_y - eye_r - 4
    draw.line(
        [left_eye_x - eye_r - 2, brow_y, left_eye_x + eye_r + 2, brow_y - 1],
        fill=hair,
        width=2,
    )
    draw.line(
        [right_eye_x - eye_r - 2, brow_y - 1, right_eye_x + eye_r + 2, brow_y],
        fill=hair,
        width=2,
    )

    # Smile / mouth
    mouth_y = head_y0 + int(head_h * 0.72)
    mouth_w = int(head_w * 0.28)
    mouth_x0 = (width - mouth_w) // 2
    draw.arc(
        [mouth_x0, mouth_y - 6, mouth_x0 + mouth_w, mouth_y + 8],
        start=20,
        end=160,
        fill=(120, 50, 50),
        width=2,
    )

    return img


def generate_signature(width: int, height: int, rng: random.Random) -> Image.Image:
    """Generate a procedural cursive specimen signature stroke.

    Args:
        width: Width in pixels.
        height: Height in pixels.
        rng: Seeded random instance.

    Returns:
        Transparent RGBA Pillow image with procedural ink strokes.
    """
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    num_points = rng.randint(12, 18)
    xs = [int(width * (0.05 + 0.9 * (i / (num_points - 1)))) for i in range(num_points)]
    base_y = height * 0.55

    points: list[tuple[int, int]] = []
    for i, x in enumerate(xs):
        # Initial flourish loop
        if i == 0:
            y = int(base_y - height * 0.3)
        elif i == 1:
            y = int(base_y + height * 0.25)
        else:
            jitter = rng.uniform(-0.25, 0.25) * height
            y = int(max(4, min(height - 4, base_y + jitter)))
        points.append((x, y))

    ink_color = (20, 25, 80, 240)
    for i in range(len(points) - 1):
        draw.line([points[i], points[i + 1]], fill=ink_color, width=2)

    return img


def generate_qr_code(data: str, size: tuple[int, int]) -> Image.Image:
    """Generate a high-contrast QR code image scaled to target dimensions.

    Args:
        data: String payload encoded inside the QR code.
        size: Target (width, height) in pixels.

    Returns:
        Pillow RGBA Image containing the QR code.
    """
    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=2,
    )
    qr.add_data(data)
    qr.make(fit=True)
    qr_img = qr.make_image(fill_color="black", back_color="white").convert("RGBA")
    return qr_img.resize(size, Image.Resampling.NEAREST)
