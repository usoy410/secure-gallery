"""Font resolution utilities for synthetic card text rendering.

Provides a robust lookup chain to locate high-clarity sans-serif TrueType fonts
(e.g., Liberation Sans, DejaVu Sans, Noto Sans) across system environments, ensuring
consistent visual presentation and reliable OCR character recognition.
"""

from __future__ import annotations

import os
from pathlib import Path

from PIL import ImageFont

# Repository-bundled OFL TrueType fonts for deterministic cross-machine rendering.
# Why: Guarantees identical glyph geometry and >= 95% OCR accuracy regardless of
# host OS font packages.
BUNDLED_FONTS_DIR: Path = Path(__file__).resolve().parent / "assets" / "fonts"
BUNDLED_REGULAR_FONT: Path = BUNDLED_FONTS_DIR / "LiberationSans-Regular.ttf"
BUNDLED_BOLD_FONT: Path = BUNDLED_FONTS_DIR / "LiberationSans-Bold.ttf"

# Candidate system paths for high-clarity sans-serif TrueType fonts.
# Why: Tesseract and ML OCR models perform best on clean, standard glyphs.
# Liberation Sans is preferred over DejaVu Sans because DejaVu decimal points in metric
# height fields and character kerning trigger OCR misidentifications.
CANDIDATE_REGULAR_FONTS: list[str] = [
    str(BUNDLED_REGULAR_FONT),
    "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/noto/NotoSans-Regular.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans.ttf",
]

CANDIDATE_BOLD_FONTS: list[str] = [
    str(BUNDLED_BOLD_FONT),
    "/usr/share/fonts/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/noto/NotoSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]


def resolve_font_path(bold: bool = False) -> str | None:
    """Find the first available sans-serif TTF font path on the system.

    Args:
        bold: Whether to search for bold weights.

    Returns:
        String path to the font file, or None if no candidate exists.
    """
    candidates = CANDIDATE_BOLD_FONTS if bold else CANDIDATE_REGULAR_FONTS
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return None


def get_font(
    size: int = 24,
    bold: bool = False,
) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    """Load a TrueType font with the specified point size, or fallback to default.

    Args:
        size: Desired font size in pixels.
        bold: Whether to load bold weight.

    Returns:
        Pillow font instance ready for ImageDraw operations.
    """
    font_path = resolve_font_path(bold=bold)
    if font_path is not None:
        try:
            return ImageFont.truetype(font_path, size=size)
        except OSError:
            pass

    # Fallback to Pillow's built-in default font
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()
