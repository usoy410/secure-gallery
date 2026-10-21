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
    """Verify curated photo list has all required fields and valid licenses offline."""
    required_fields = {
        "id",
        "filename",
        "url",
        "file_page_url",
        "author",
        "license",
        "license_short_name",
        "license_url",
        "description",
        "retrieval_date",
        "sha1",
        "sha256",
        "source",
    }
    allowed_licenses = {"Public domain", "CC0"}

    assert len(PUBLIC_DOMAIN_PHOTOS) >= 5
    for p in PUBLIC_DOMAIN_PHOTOS:
        # All required metadata fields present and non-empty
        missing = required_fields - set(p.keys())
        assert not missing, f"Missing fields in {p.get('id')}: {missing}"
        for fld in required_fields:
            assert isinstance(p[fld], str) and len(p[fld]) > 0

        # License short name must strictly be Public domain or CC0
        assert (
            p["license_short_name"] in allowed_licenses
        ), f"Unapproved license {p['license_short_name']} in {p['id']}"

        # Valid sha1 (40 hex chars) and sha256 (64 hex chars)
        assert len(p["sha1"]) == 40 and all(c in "0123456789abcdefABCDEF" for c in p["sha1"])
        assert len(p["sha256"]) == 64 and all(c in "0123456789abcdefABCDEF" for c in p["sha256"])

        # URL points to Wikimedia Commons
        assert p["url"].startswith("https://upload.wikimedia.org/wikipedia/commons/")
        assert p["file_page_url"].startswith("https://commons.wikimedia.org/wiki/File:")


def test_unknown_and_corrupt_photos_skipped_with_warning(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Verify unknown or hash-mismatched files in photos_dir are skipped with warning."""
    # 1. Place an unknown random image
    unknown_img_path = tmp_path / "unknown_random_image.jpg"
    test_img = Image.new("RGB", (200, 200), (120, 150, 180))
    test_img.save(unknown_img_path)

    # 2. Place a file with a curated filename but corrupt / wrong hash
    corrupt_entry = PUBLIC_DOMAIN_PHOTOS[0]
    corrupt_img_path = tmp_path / corrupt_entry["filename"]
    corrupt_img = Image.new("RGB", (100, 100), (255, 0, 0))
    corrupt_img.save(corrupt_img_path)

    # Generate photo negative sample
    with caplog.at_level("WARNING"):
        img, label = generate_negative_sample("photo", seed=42, photos_dir=tmp_path)

    assert isinstance(img, Image.Image)
    # Both files must have been skipped and logged with warnings
    assert "Skipping unknown photo in photos_dir" in caplog.text
    assert "Skipping photo" in caplog.text and "hash mismatch" in caplog.text

    # Must fall back to procedural photo and NOT be labeled with unverified CC0
    assert label.get("is_procedural") is True
    assert label["photo_source"] == "procedurally_generated_scene"


def test_curated_photo_accepted_when_name_and_hash_match(tmp_path: Path) -> None:
    """Verify that a photo matching both filename and sha256 in the curated list is accepted."""
    import hashlib

    entry = PUBLIC_DOMAIN_PHOTOS[0]
    target_path = tmp_path / entry["filename"]

    # Write dummy bytes and temporarily mock/set hash to match
    dummy_bytes = b"VERIFIED_CURATED_IMAGE_DATA_12345"
    with open(target_path, "wb") as f:
        f.write(dummy_bytes)

    expected_sha256 = hashlib.sha256(dummy_bytes).hexdigest()
    expected_sha1 = hashlib.sha1(dummy_bytes).hexdigest()

    # Temporarily monkeypatch the entry's hashes to match our file
    orig_sha256 = entry["sha256"]
    orig_sha1 = entry["sha1"]
    entry["sha256"] = expected_sha256
    entry["sha1"] = expected_sha1

    try:
        # Create a small valid JPEG so PIL can open it
        valid_img = Image.new("RGB", (200, 200), (200, 200, 200))
        valid_img.save(target_path, format="JPEG")
        with open(target_path, "rb") as f:
            jpeg_bytes = f.read()
        entry["sha256"] = hashlib.sha256(jpeg_bytes).hexdigest()
        entry["sha1"] = hashlib.sha1(jpeg_bytes).hexdigest()

        img, label = generate_negative_sample("photo", seed=42, photos_dir=tmp_path)
        assert isinstance(img, Image.Image)
        assert label.get("is_procedural") is None or label.get("is_procedural") is False
        assert label["photo_source"] == entry["source"]
        assert label["photo_license"] == entry["license"]
    finally:
        entry["sha256"] = orig_sha256
        entry["sha1"] = orig_sha1


def test_curated_photos_cycled_and_distinct_with_photo_index(tmp_path: Path) -> None:
    """Verify photo negative generator selects curated photos by index across samples."""
    import hashlib

    orig_hashes = [(p["id"], p["sha256"], p["sha1"]) for p in PUBLIC_DOMAIN_PHOTOS]
    for i, entry in enumerate(PUBLIC_DOMAIN_PHOTOS):
        target_path = tmp_path / entry["filename"]
        img = Image.new("RGB", (100, 100), (10 * i, 20 * i, 30 * i))
        img.save(target_path, format="JPEG")
        with open(target_path, "rb") as f:
            b = f.read()
        entry["sha256"] = hashlib.sha256(b).hexdigest()
        entry["sha1"] = hashlib.sha1(b).hexdigest()

    try:
        chosen_sources = []
        for idx in range(len(PUBLIC_DOMAIN_PHOTOS)):
            # With seed differing by 1000 like in dataset generation
            sample_seed = 42 + idx * 1000
            _, label = generate_negative_sample(
                "photo", seed=sample_seed, photos_dir=tmp_path, photo_index=idx
            )
            chosen_sources.append(label["photo_source"])

        # All curated photos must be selected distinctly
        assert len(set(chosen_sources)) == len(PUBLIC_DOMAIN_PHOTOS)
        assert len(set(chosen_sources)) > 1
    finally:
        hash_map = {item[0]: (item[1], item[2]) for item in orig_hashes}
        for entry in PUBLIC_DOMAIN_PHOTOS:
            if entry["id"] in hash_map:
                entry["sha256"], entry["sha1"] = hash_map[entry["id"]]


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
