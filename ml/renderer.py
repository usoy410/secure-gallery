"""Card rendering engine for synthetic Philippine ID and payment card benchmark.

Renders simplified reconstructed card designs according to templates/schema.json,
drawing field ground-truth within exact normalized polygon boundaries and applying
the mandatory diagonal 'SPECIMEN – NOT VALID' watermark.
"""

from __future__ import annotations

import random
from typing import Any

import numpy as np
from PIL import Image, ImageDraw

from ml.avatar import generate_avatar, generate_qr_code, generate_signature
from ml.fonts import get_font
from ml.jitter import (
    apply_field_jitter_to_polygon,
    compute_field_offset,
    compute_font_size_jitter,
    compute_print_offset,
)

# Dimensions matching ISO/IEC 7810 ID-1 standard aspect ratio (~1.5858)
DEFAULT_CARD_WIDTH: int = 1012
DEFAULT_CARD_HEIGHT: int = 638


def _get_bounding_box(polygon: list[list[int]]) -> tuple[int, int, int, int]:
    """Compute (min_x, min_y, max_x, max_y) bounding box for a pixel polygon."""
    xs = [pt[0] for pt in polygon]
    ys = [pt[1] for pt in polygon]
    return (min(xs), min(ys), max(xs), max(ys))


def _draw_card_background(
    img: Image.Image,
    template_id: str,
    doc_class: str,
    width: int,
    height: int,
) -> None:
    """Render a simplified non-artwork reconstructed card background."""
    draw = ImageDraw.Draw(img)

    if doc_class == "philsys":
        if "ephilid" in template_id:
            # Paper printout ePhilID: crisp white with fine folding guide borders
            draw.rectangle(
                [0, 0, width - 1, height - 1],
                fill=(253, 253, 253),
                outline=(210, 210, 215),
                width=2,
            )
            draw.line(
                [0, int(height * 0.17), width, int(height * 0.17)],
                fill=(180, 180, 190),
                width=1,
            )
        else:
            # Polycarbonate PhilID: warm cream base with top national tricolor accent bar
            draw.rectangle(
                [0, 0, width - 1, height - 1],
                fill=(246, 244, 238),
                outline=(200, 195, 185),
                width=2,
            )
            draw.rectangle([0, 0, width, int(height * 0.015)], fill=(30, 75, 150))
            draw.rectangle([0, int(height * 0.015), width, int(height * 0.022)], fill=(195, 35, 35))
    elif doc_class == "lto":
        if "previous" in template_id:
            # Previous 2017-2021 blue design
            draw.rectangle(
                [0, 0, width - 1, height - 1],
                fill=(212, 232, 250),
                outline=(170, 195, 220),
                width=2,
            )
            draw.rectangle([0, 0, width, int(height * 0.02)], fill=(20, 50, 110))
        else:
            # Current pearlized white design
            draw.rectangle(
                [0, 0, width - 1, height - 1],
                fill=(248, 249, 252),
                outline=(195, 200, 210),
                width=2,
            )
            draw.rectangle([0, 0, width, int(height * 0.015)], fill=(25, 70, 145))
            draw.rectangle([0, int(height * 0.015), width, int(height * 0.022)], fill=(190, 40, 40))
    elif doc_class == "payment_card":
        if "back" in template_id:
            # Dark card reverse with magnetic stripe and signature panel
            draw.rectangle(
                [0, 0, width - 1, height - 1],
                fill=(32, 38, 54),
                outline=(50, 58, 80),
                width=2,
            )
            # Magnetic stripe
            stripe_y0 = int(height * 0.10)
            stripe_y1 = int(height * 0.32)
            draw.rectangle([0, stripe_y0, width, stripe_y1], fill=(16, 16, 20))
            # Signature panel
            sig_y0 = int(height * 0.44)
            sig_y1 = int(height * 0.60)
            draw.rectangle(
                [int(width * 0.06), sig_y0, int(width * 0.66), sig_y1],
                fill=(238, 238, 242),
            )
        else:
            # Front of card: dark navy gradient feel with EMV chip
            draw.rectangle(
                [0, 0, width - 1, height - 1],
                fill=(26, 34, 56),
                outline=(48, 60, 90),
                width=2,
            )
            # Simulated EMV Smart Chip
            chip_x0 = int(width * 0.10)
            chip_y0 = int(height * 0.28)
            chip_x1 = int(width * 0.22)
            chip_y1 = int(height * 0.44)
            draw.rounded_rectangle(
                [chip_x0, chip_y0, chip_x1, chip_y1],
                radius=8,
                fill=(215, 185, 80),
                outline=(160, 135, 50),
                width=2,
            )
            chip_mid_x = (chip_x0 + chip_x1) // 2
            chip_mid_y = (chip_y0 + chip_y1) // 2
            draw.line([chip_mid_x, chip_y0, chip_mid_x, chip_y1], fill=(160, 135, 50), width=1)
            draw.line([chip_x0, chip_mid_y, chip_x1, chip_mid_y], fill=(160, 135, 50), width=1)


# Watermark inks: red on light cards, light grey on dark ones, so it shows on every card (#34).
WATERMARK_RED = (215, 35, 35, 45)
WATERMARK_LIGHT = (245, 245, 245, 70)
# Cards whose mean luminance under the watermark is below this get the light ink.
WATERMARK_DARK_LUMINANCE = 110
# Smallest mean change (0-255, largest channel) the watermark must make on its own pixels.
MIN_WATERMARK_CONTRAST = 20.0


def _draw_watermark(img: Image.Image, width: int, height: int) -> float:
    """Render the mandatory visible diagonal 'SPECIMEN – NOT VALID' watermark.

    Why: CLAUDE.md and Issue #5 require every synthetic image to bear this watermark
    to prevent misuse, while remaining transparent enough for OCR character verification.
    The ink depends on the card: faint red disappears on a dark card (#34).

    Returns:
        The watermark's contrast: the mean change it made on its own pixels, largest channel.
    """
    wm_font = get_font(size=52, bold=True)
    wm_text = "SPECIMEN – NOT VALID"

    # Measure text bounding box
    dummy_draw = ImageDraw.Draw(Image.new("RGBA", (width, height), (0, 0, 0, 0)))
    bbox = dummy_draw.textbbox((0, 0), wm_text, font=wm_font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    pad = 40

    def rotated_text(fill: tuple[int, int, int, int]) -> Image.Image:
        # Render into a separate canvas and rotate 24 degrees diagonally
        txt_img = Image.new("RGBA", (text_w + pad * 2, text_h + pad * 2), (0, 0, 0, 0))
        ImageDraw.Draw(txt_img).text((pad, pad), wm_text, fill=fill, font=wm_font)
        return txt_img.rotate(24, expand=True, resample=Image.Resampling.BICUBIC)

    # Where the glyphs land, to pick an ink that shows on this card
    shape = rotated_text((255, 255, 255, 255))
    pos_x = (width - shape.width) // 2
    pos_y = (height - shape.height) // 2
    mask = Image.new("L", (width, height), 0)
    mask.paste(shape.getchannel("A"), (pos_x, pos_y))
    glyph = np.asarray(mask) > 127
    before = np.asarray(img.convert("RGB"), dtype=np.float32)
    luminance = before @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    dark = glyph.any() and float(luminance[glyph].mean()) < WATERMARK_DARK_LUMINANCE
    ink = WATERMARK_LIGHT if dark else WATERMARK_RED

    rotated = rotated_text(ink)
    wm_overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    # A plain paste keeps the ink's own alpha. Pasting with the image as its own mask, as
    # before #34, applied the alpha twice (45 became about 8) and left the mark faint.
    wm_overlay.paste(rotated, (pos_x, pos_y))

    # Alpha composite onto base image
    composite = Image.alpha_composite(img.convert("RGBA"), wm_overlay)
    img.paste(composite)

    after = np.asarray(img.convert("RGB"), dtype=np.float32)
    if not glyph.any():
        return 0.0
    return float(np.abs(after - before).max(axis=2)[glyph].mean())


def render_card(
    template: dict[str, Any],
    field_values: dict[str, str],
    seed: int = 42,
    card_width: int = DEFAULT_CARD_WIDTH,
    card_height: int = DEFAULT_CARD_HEIGHT,
    jitter: bool = False,
) -> tuple[Image.Image, list[dict[str, Any]]]:
    """Render a clean ID or payment card image with exact ground-truth field polygons.

    Args:
        template: Loaded template dictionary conforming to templates/schema.json.
        field_values: Mapping of field types to generated string values.
        seed: Deterministic integer seed.
        card_width: Card image width in pixels.
        card_height: Card image height in pixels.
        jitter: Whether to apply layout jitter (field offset, font size, print offset).

    Returns:
        Tuple of (clean_card_image, list_of_field_ground_truth_dictionaries).
    """
    rng = random.Random(seed)
    template_id = template["id"]
    doc_class = template["document_class"]

    img = Image.new("RGBA", (card_width, card_height), (255, 255, 255, 255))
    _draw_card_background(img, template_id, doc_class, card_width, card_height)

    draw = ImageDraw.Draw(img)
    # Both sides of the payment card share the dark navy body, so both use light text.
    is_dark_card = doc_class == "payment_card"
    default_text_color = (255, 255, 255) if is_dark_card else (15, 15, 15)

    if jitter:
        jitter_rng = random.Random(seed ^ 0x4C61796F)
        print_offset = compute_print_offset(jitter_rng, card_width, card_height)
    else:
        jitter_rng = None
        print_offset = (0, 0)

    field_ground_truth: list[dict[str, Any]] = []

    for field in template.get("fields", []):
        name = field["name"]
        ftype = field["type"]
        sensitive = field["sensitive"]
        polygon_norm = field["polygon"]

        # Convert normalized [0, 1] polygon to exact pixel coordinates
        base_polygon: list[list[int]] = [
            [int(round(pt[0] * card_width)), int(round(pt[1] * card_height))]
            for pt in polygon_norm
        ]

        if jitter and jitter_rng is not None:
            field_offset = compute_field_offset(jitter_rng, card_width)
            pixel_polygon = apply_field_jitter_to_polygon(base_polygon, print_offset, field_offset)
        else:
            pixel_polygon = base_polygon

        min_x, min_y, max_x, max_y = _get_bounding_box(pixel_polygon)
        box_w = max_x - min_x
        box_h = max_y - min_y

        value = field_values.get(ftype, "")

        def resolve_size(base_sz: int) -> int:
            if jitter and jitter_rng is not None:
                return compute_font_size_jitter(jitter_rng, base_sz)
            return base_sz

        if ftype == "face_photo":
            avatar_img = generate_avatar(box_w, box_h, rng)
            img.paste(avatar_img, (min_x, min_y), avatar_img)
        elif ftype == "signature":
            sig_img = generate_signature(box_w, box_h, rng)
            img.paste(sig_img, (min_x, min_y), sig_img)
        elif ftype == "qr_code":
            qr_img = generate_qr_code(value or f"ID:{seed}", (box_w, box_h))
            img.paste(qr_img, (min_x, min_y), qr_img)
        elif ftype in ("card_number", "pcn", "pcn_psn", "license_number"):
            font = get_font(size=resolve_size(25), bold=True)
            text_color = (255, 255, 255) if is_dark_card else (0, 0, 0)
            draw.text((min_x + 6, min_y + 4), value, fill=text_color, font=font)
        elif ftype in ("full_name", "name", "cardholder_name"):
            font = get_font(size=resolve_size(22), bold=True)
            text_color = (255, 255, 255) if is_dark_card else (0, 0, 0)
            draw.text((min_x + 6, min_y + 4), value, fill=text_color, font=font)
        elif ftype == "address":
            font = get_font(size=resolve_size(18), bold=False)
            text_color = (255, 255, 255) if is_dark_card else (10, 10, 10)
            # If address contains comma, split street and city across two lines for clean fit
            if "," in value and len(value) > 28:
                parts = value.split(",", 1)
                line1 = parts[0].strip()
                line2 = parts[1].strip()
                rendered_value = f"{line1}\n{line2}"
            else:
                rendered_value = value
            value = rendered_value
            draw.multiline_text(
                (min_x + 6, min_y + 4),
                value,
                fill=text_color,
                font=font,
                spacing=4,
            )
        elif ftype == "headings":
            font = get_font(size=resolve_size(18), bold=True)
            header_color = (
                (20, 60, 130) if doc_class in ("philsys", "lto") else default_text_color
            )
            draw.multiline_text(
                (min_x + 6, min_y + 4),
                value,
                fill=header_color,
                font=font,
                spacing=3,
            )
        elif ftype == "cvv":
            font = get_font(size=resolve_size(24), bold=True)
            # The CVV sits right of the signature panel, on the navy body. Give it its
            # own white box (as real cards do) so the black digits stay readable.
            draw.polygon([tuple(pt) for pt in pixel_polygon], fill=(250, 250, 250))
            draw.text((min_x + 8, min_y + 4), value, fill=(0, 0, 0), font=font)
        else:
            font = get_font(size=resolve_size(20), bold=False)
            draw.text((min_x + 6, min_y + 4), value, fill=default_text_color, font=font)

        field_ground_truth.append({
            "name": name,
            "type": ftype,
            "sensitive": sensitive,
            "polygon": pixel_polygon,
            "value": value,
        })

    contrast = _draw_watermark(img, card_width, card_height)
    rgb = img.convert("RGB")
    # Read by generate_single_sample into the label, for the release check (#34).
    rgb.info["watermark_contrast"] = round(contrast, 2)
    return rgb, field_ground_truth
