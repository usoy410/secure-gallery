"""Pytest test suite for negative non-ID sample generator.

Validates:
1. Generates all 6 negative sample types: receipt, loyalty_card, blank_card,
   card_rectangle, screenshot, and photo.
2. Every negative sample produces class='negative', document_class='negative', and fields=[].
3. Negative images adhere to standard 1600x1200 canvas dimensions.
4. Procedural photo generator produces varied scenes without requiring network access.
5. Deterministic seeding produces byte-identical output.
"""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from PIL import Image

from ml.fetch_photos import PUBLIC_DOMAIN_PHOTOS, generate_procedural_photo
from ml.negatives import (
    NEGATIVE_TYPES,
    generate_blank_card_image,
    generate_loyalty_card_image,
    generate_negative_sample,
    generate_receipt_image,
)


@pytest.mark.parametrize("neg_type", NEGATIVE_TYPES)
def test_generates_every_negative_type(neg_type: str, tmp_path: Path) -> None:
    """Verify each of the 6 negative types generates valid PIL image and ground-truth label."""
    img, label = generate_negative_sample(neg_type, seed=123, photos_dir=tmp_path)

    assert isinstance(img, Image.Image)
    assert img.width == 1600
    assert img.height == 1200

    assert label["class"] == "negative"
    assert label["document_class"] == "negative"
    assert label["negative_type"] == neg_type
    assert label["is_negative"] is True
    assert label["fields"] == []
    assert label["card_corners"] == []
    assert label["base_image_id"].startswith(f"neg_{neg_type}_")


def test_procedural_photo_fallback_without_network() -> None:
    """Verify procedural photo generator creates scenic non-ID images offline."""
    img, meta = generate_procedural_photo(seed=999, width=1600, height=1200)

    assert isinstance(img, Image.Image)
    assert img.width == 1600
    assert img.height == 1200
    assert meta["is_procedural"] is True
    assert "license" in meta
    assert "source" in meta


def test_curated_public_domain_photo_metadata() -> None:
    """Verify curated public domain photos list has complete license and attribution info."""
    assert len(PUBLIC_DOMAIN_PHOTOS) >= 5
    for p in PUBLIC_DOMAIN_PHOTOS:
        assert p["url"].startswith("https://upload.wikimedia.org/")
        assert "CC0" in p["license"] or "Public Domain" in p["license"]
        assert len(p["description"]) > 10
        assert len(p["source"]) > 5


def test_individual_negative_subcomponents() -> None:
    """Verify individual subcomponent generators render proper dimensions and visual elements."""
    receipt = generate_receipt_image(seed=42)
    assert receipt.width == 500
    assert receipt.height == 900
    assert receipt.mode == "RGBA"

    loyalty = generate_loyalty_card_image(seed=42)
    assert loyalty.width == 1000
    assert loyalty.height == 630
    assert loyalty.mode == "RGBA"

    blank = generate_blank_card_image(seed=42)
    assert blank.width == 1000
    assert blank.height == 630
    assert blank.mode == "RGBA"


def test_negative_generation_determinism(tmp_path: Path) -> None:
    """Verify negative sample generator produces byte-identical PNG output for the same seed."""
    for n_type in NEGATIVE_TYPES:
        img1, lbl1 = generate_negative_sample(n_type, seed=777, photos_dir=tmp_path)
        img2, lbl2 = generate_negative_sample(n_type, seed=777, photos_dir=tmp_path)

        buf1 = io.BytesIO()
        buf2 = io.BytesIO()
        img1.save(buf1, format="PNG")
        img2.save(buf2, format="PNG")

        assert buf1.getvalue() == buf2.getvalue(), f"Determinism failure for {n_type}"
        assert lbl1 == lbl2
