"""Layout jitter engine for synthetic Philippine ID and payment card rendering.

Applies subtle printing and production variations at render time:
1. Global print-offset shift across the card canvas.
2. Individual field position displacement up to +/- 2% of card width.
3. Font size jitter within +/- 5% of base font size.

Why: SECURE_GALLERY.md §10.1 and §15 note that Philippine IDs have minor production
and printing variances. Layout jitter prevents layout-first redaction from being evaluated
only against synthetic images with perfectly aligned fields.
"""

from __future__ import annotations

import random


def compute_print_offset(
    rng: random.Random,
    card_width: int,
    card_height: int,
    max_offset_ratio: float = 0.008,
) -> tuple[int, int]:
    """Compute global print-offset shift across the card substrate.

    Why: ID card physical printing processes exhibit small substrate-to-print
    registration offsets, typically within +/- 0.8% of card dimensions.

    Args:
        rng: Deterministic random generator.
        card_width: Width of card in pixels.
        card_height: Height of card in pixels.
        max_offset_ratio: Maximum offset as a fraction of card dimensions (default: 0.008).

    Returns:
        Tuple of (dx, dy) pixel offsets.
    """
    max_dx = max(1, int(round(card_width * max_offset_ratio)))
    max_dy = max(1, int(round(card_height * max_offset_ratio)))
    dx = rng.randint(-max_dx, max_dx)
    dy = rng.randint(-max_dy, max_dy)
    return (dx, dy)


def compute_field_offset(
    rng: random.Random,
    card_width: int,
    max_offset_ratio: float = 0.02,
) -> tuple[int, int]:
    """Compute per-field displacement up to +/- 2% of card width.

    Why: Text and photo placements on identity cards vary slightly due to
    firmware and layout rendering variances across printing bureaus.

    Args:
        rng: Deterministic random generator.
        card_width: Width of card in pixels.
        max_offset_ratio: Maximum offset as fraction of card width (default: 0.02 = 2%).

    Returns:
        Tuple of (field_dx, field_dy) pixel offsets.
    """
    max_offset = max(1, int(round(card_width * max_offset_ratio)))
    dx = rng.randint(-max_offset, max_offset)
    dy = rng.randint(-max_offset, max_offset)
    return (dx, dy)


def compute_font_size_jitter(
    rng: random.Random,
    base_size: int,
    variation_ratio: float = 0.05,
    min_size: int = 8,
) -> int:
    """Compute font size with up to +/- 5% variation.

    Why: Font rasterization DPI and rendering sub-pixel variations slightly alter
    rendered glyph proportions on synthetic benchmark samples.

    Args:
        rng: Deterministic random generator.
        base_size: Original unjittered font size.
        variation_ratio: Maximum variation fraction (default: 0.05 = +/-5%).
        min_size: Minimum allowable font size.

    Returns:
        Jittered font size as an integer.
    """
    factor = rng.uniform(1.0 - variation_ratio, 1.0 + variation_ratio)
    return max(min_size, int(round(base_size * factor)))


def apply_field_jitter_to_polygon(
    polygon: list[list[int]],
    print_offset: tuple[int, int],
    field_offset: tuple[int, int],
) -> list[list[int]]:
    """Apply global print offset and individual field offset to a polygon.

    Why: Ground-truth bounding polygons must shift identically with the rendered
    visual field content so that labels remain 100% pixel-accurate.

    Args:
        polygon: List of [x, y] coordinates.
        print_offset: (print_dx, print_dy) offset.
        field_offset: (field_dx, field_dy) offset.

    Returns:
        New list of [x, y] coordinates shifted by total offset.
    """
    total_dx = print_offset[0] + field_offset[0]
    total_dy = print_offset[1] + field_offset[1]
    return [[pt[0] + total_dx, pt[1] + total_dy] for pt in polygon]

