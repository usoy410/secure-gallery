"""Failed redaction generator for evaluating Secure Gallery leak-test framework.

SECURE_GALLERY.md §9.3 & §10.1 require test images with intentionally broken redactions:
1. 'complete': Ground-truth 100% solid-fill opaque mask (expected leak: none).
2. 'partial': Redaction box covering only 30%–70% of field (expected leak: partial_text_exposed).
3. 'misplaced': Offset box shifted away from field (expected leak: offset_uncovered_field).
4. 'ineffective': Semi-transparent or blur fill (expected leak: transparent_or_blur_fill_legible).

Each sample is labeled with its expected leak, covered fraction, and residual detectability.
"""

from __future__ import annotations

import copy
import logging
import random
from typing import Any

from PIL import Image, ImageDraw, ImageFilter

logger = logging.getLogger(__name__)

FAILED_REDACTION_TYPES: tuple[str, ...] = (
    "complete",
    "partial",
    "misplaced",
    "ineffective",
)

# Common sensitive field types to target for redaction across Philippine IDs and cards
SENSITIVE_FIELD_TYPES: set[str] = {
    "pcn",
    "license_number",
    "card_number",
    "full_name",
    "cardholder_name",
    "address",
    "date_of_birth",
    "cvv",
}


def _get_field_bbox(polygon: list[list[int | float]]) -> tuple[int, int, int, int]:
    """Compute axis-aligned integer bounding box [min_x, min_y, max_x, max_y] from polygon.

    Args:
        polygon: List of coordinate pairs [[x1, y1], [x2, y2], ...].

    Returns:
        Tuple of (min_x, min_y, max_x, max_y).
    """
    xs = [p[0] for p in polygon]
    ys = [p[1] for p in polygon]
    return (int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys)))


def apply_failed_redaction(
    img: Image.Image,
    label: dict[str, Any],
    redaction_type: str,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply an intentional redaction pattern to an image.

    Supports complete, partial, misplaced, and ineffective redactions.

    Args:
        img: Input image (clean or composited card).
        label: Ground truth label dictionary.
        redaction_type: One of ('complete', 'partial', 'misplaced', 'ineffective').
        seed: Deterministic integer seed.

    Returns:
        Tuple of (modified_PIL_Image, updated_label_dict).
    """
    if redaction_type not in FAILED_REDACTION_TYPES:
        raise ValueError(
            f"Unknown redaction_type '{redaction_type}'. Expected one of: {FAILED_REDACTION_TYPES}"
        )

    rng = random.Random(seed)
    result_img = img.copy().convert("RGBA")
    draw = ImageDraw.Draw(result_img)

    fields = label.get("fields", [])
    targets_to_use = SENSITIVE_FIELD_TYPES

    candidate_fields = [
        f for f in fields if f.get("type") in targets_to_use and f.get("polygon")
    ]
    # Fallback to any available fields with polygons if no sensitive fields present
    if not candidate_fields:
        candidate_fields = [f for f in fields if f.get("polygon")]

    if not candidate_fields:
        logger.warning("No fields found to apply redaction to; returning image unchanged.")
        res_label = copy.deepcopy(label)
        res_label["is_failed_redaction"] = False
        res_label["redaction_type"] = "none"
        res_label["expected_leak"] = "no_fields_to_redact"
        return result_img.convert("RGB"), res_label

    # Select fields to redact (deterministic subset or all sensitive fields)
    num_to_redact = max(1, min(len(candidate_fields), rng.randint(1, 3)))
    selected_fields = rng.sample(candidate_fields, num_to_redact)

    redaction_boxes: list[list[int]] = []
    covered_fractions: list[float] = []

    for fld in selected_fields:
        min_x, min_y, max_x, max_y = _get_field_bbox(fld["polygon"])
        fw = max(1, max_x - min_x)
        fh = max(1, max_y - min_y)

        if redaction_type == "complete":
            # Solid opaque box covering 100% of the field with slight padding
            pad = 2
            bx1 = max(0, min_x - pad)
            by1 = max(0, min_y - pad)
            bx2 = min(result_img.width, max_x + pad)
            by2 = min(result_img.height, max_y + pad)
            draw.rectangle([bx1, by1, bx2, by2], fill=(0, 0, 0, 255))
            redaction_boxes.append([bx1, by1, bx2, by2])

            # Measure real covered fraction to avoid hardcoding assumptions
            inter_w = max(0, min(max_x, bx2) - max(min_x, bx1))
            inter_h = max(0, min(max_y, by2) - max(min_y, by1))
            field_area = max(1, fw * fh)
            covered_fractions.append(round((inter_w * inter_h) / field_area, 3))

        elif redaction_type == "partial":
            # Cover between 30% and 70% of the field area
            frac = rng.uniform(0.30, 0.70)
            side = rng.choice(["left", "right", "top", "bottom"])

            if side == "left":
                bx1, by1 = min_x, min_y
                bx2, by2 = int(min_x + fw * frac), max_y
            elif side == "right":
                bx1, by1 = int(max_x - fw * frac), min_y
                bx2, by2 = max_x, max_y
            elif side == "top":
                bx1, by1 = min_x, min_y
                bx2, by2 = max_x, int(min_y + fh * frac)
            else:  # bottom
                bx1, by1 = min_x, int(max_y - fh * frac)
                bx2, by2 = max_x, max_y

            # Clamp coordinates within canvas boundaries
            bx1 = max(0, min(result_img.width, bx1))
            bx2 = max(0, min(result_img.width, bx2))
            by1 = max(0, min(result_img.height, by1))
            by2 = max(0, min(result_img.height, by2))
            if bx2 < bx1:
                bx1, bx2 = bx2, bx1
            if by2 < by1:
                by1, by2 = by2, by1

            draw.rectangle([bx1, by1, bx2, by2], fill=(0, 0, 0, 255))
            redaction_boxes.append([bx1, by1, bx2, by2])

            # Measure actual intersection to record the exact measured coverage, not requested frac
            inter_w = max(0, min(max_x, bx2) - max(min_x, bx1))
            inter_h = max(0, min(max_y, by2) - max(min_y, by1))
            field_area = max(1, fw * fh)
            measured_frac = (inter_w * inter_h) / field_area
            covered_fractions.append(round(measured_frac, 3))

        elif redaction_type == "misplaced":
            # Shift box away from field by at least fw/fh + 1px so the field remains
            # completely exposed without any 1px edge overlap even at offset 0.
            # Select only candidate directions that have sufficient space inside the image canvas
            # to prevent clamping from pushing the box back over the field.
            room_right = result_img.width - (max_x + 1 + fw)
            room_left = min_x - 1 - fw
            room_down = result_img.height - (max_y + 1 + fh)
            room_up = min_y - 1 - fh

            candidate_dirs: list[str] = []
            if room_right >= 0:
                candidate_dirs.append("right")
            if room_left >= 0:
                candidate_dirs.append("left")
            if room_down >= 0:
                candidate_dirs.append("down")
            if room_up >= 0:
                candidate_dirs.append("up")

            if candidate_dirs:
                chosen_dir = rng.choice(candidate_dirs)
            else:
                # If no direction has full clearance, pick direction with largest room
                rooms = {"right": room_right, "left": room_left, "down": room_down, "up": room_up}
                chosen_dir = max(rooms, key=rooms.get)

            if chosen_dir == "right":
                max_shift = min(max(0, room_right), int(fw * 0.5))
                offset = rng.randint(0, max_shift)
                bx1 = max_x + 1 + offset
                bx2 = bx1 + fw
                by1 = min_y
                by2 = min_y + fh
            elif chosen_dir == "left":
                max_shift = min(max(0, room_left), int(fw * 0.5))
                offset = rng.randint(0, max_shift)
                bx2 = min_x - 1 - offset
                bx1 = bx2 - fw
                by1 = min_y
                by2 = min_y + fh
            elif chosen_dir == "down":
                max_shift = min(max(0, room_down), int(fh * 0.5))
                offset = rng.randint(0, max_shift)
                by1 = max_y + 1 + offset
                by2 = by1 + fh
                bx1 = min_x
                bx2 = min_x + fw
            else:  # up
                max_shift = min(max(0, room_up), int(fh * 0.5))
                offset = rng.randint(0, max_shift)
                by2 = min_y - 1 - offset
                by1 = by2 - fh
                bx1 = min_x
                bx2 = min_x + fw

            # Robust edge clamping inside canvas to prevent negative or inverted dimensions
            bx1 = max(0, min(result_img.width, bx1))
            bx2 = max(0, min(result_img.width, bx2))
            by1 = max(0, min(result_img.height, by1))
            by2 = max(0, min(result_img.height, by2))
            if bx2 < bx1:
                bx1, bx2 = bx2, bx1
            if by2 < by1:
                by1, by2 = by2, by1

            draw.rectangle([bx1, by1, bx2, by2], fill=(0, 0, 0, 255))
            redaction_boxes.append([bx1, by1, bx2, by2])

            # Compute covered_fraction directly from real intersection with target field
            inter_w = max(0, min(max_x, bx2) - max(min_x, bx1))
            inter_h = max(0, min(max_y, by2) - max(min_y, by1))
            field_area = max(1, fw * fh)
            cov_fraction = (inter_w * inter_h) / field_area
            covered_fractions.append(round(cov_fraction, 3))

        else:  # ineffective
            style = rng.choice(["semi_transparent", "mild_blur"])
            bx1, by1 = min_x, min_y
            bx2, by2 = max_x, max_y

            if style == "semi_transparent":
                # Semi-transparent overlay box (alpha 60-100 out of 255)
                alpha = rng.randint(60, 100)
                overlay = Image.new("RGBA", result_img.size, (0, 0, 0, 0))
                o_draw = ImageDraw.Draw(overlay)
                o_draw.rectangle([bx1, by1, bx2, by2], fill=(0, 0, 0, alpha))
                result_img = Image.alpha_composite(result_img, overlay)
                draw = ImageDraw.Draw(result_img)
            else:
                # Mild blur fill (crop area blurred so text remains legible)
                crop_box = (
                    max(0, bx1 - 2),
                    max(0, by1 - 2),
                    min(result_img.width, bx2 + 2),
                    min(result_img.height, by2 + 2),
                )
                crop = result_img.crop(crop_box)
                blurred_crop = crop.filter(ImageFilter.GaussianBlur(radius=rng.uniform(1.5, 2.5)))
                result_img.paste(blurred_crop, crop_box)
                draw = ImageDraw.Draw(result_img)

            redaction_boxes.append([bx1, by1, bx2, by2])
            covered_fractions.append(1.0)

    # Determine aggregate leak expectations
    avg_covered = sum(covered_fractions) / max(1, len(covered_fractions))
    if redaction_type == "complete":
        expected_leak = "none"
        residual_detectability = "none"
        leak_fraction = 0.0
    elif redaction_type == "partial":
        expected_leak = "partial_text_exposed"
        residual_detectability = "partial"
        leak_fraction = round(1.0 - avg_covered, 3)
    elif redaction_type == "misplaced":
        expected_leak = "offset_uncovered_field"
        residual_detectability = "full"
        leak_fraction = round(1.0 - avg_covered, 3)
    else:  # ineffective
        expected_leak = "transparent_or_blur_fill_legible"
        residual_detectability = "high"
        leak_fraction = 1.0

    final_label = copy.deepcopy(label)
    final_label["is_failed_redaction"] = redaction_type != "complete"
    final_label["redaction_type"] = redaction_type
    final_label["expected_leak"] = expected_leak
    final_label["redaction"] = {
        "type": redaction_type,
        "expected_leak": expected_leak,
        "residual_detectability": residual_detectability,
        "covered_fraction": round(avg_covered, 3),
        "leak_fraction": leak_fraction,
        "target_field_types": [f["type"] for f in selected_fields],
        "redaction_boxes": redaction_boxes,
    }

    return result_img.convert("RGB"), final_label
