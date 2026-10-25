"""Tests for the public release bundle (#34): checksums, licenses and the watermark check."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml
from PIL import Image

from ml.generate import generate_single_sample, get_available_templates
from ml.release import build_release, check_watermarks, verify_release
from ml.renderer import MIN_WATERMARK_CONTRAST, _draw_watermark

TINY = {
    "dataset": {
        "name": "Tiny release",
        "seed": 0,
        "splits": {"train": 0.34, "val": 0.33, "test": 0.33},
    },
    "cards": {
        "variants": ["philsys-card", "payment-card-back"],
        "count_per_variant": 3,
        "composite": True,
        "jitter": True,
        "conditions": "blur:1",
    },
    "negatives": {"enabled": True, "count_per_type": 2, "types": ["receipt"]},
    "failed_redactions": {"enabled": True, "types": ["partial"]},
}


@pytest.fixture(scope="module")
def tiny_config(tmp_path_factory: pytest.TempPathFactory) -> Path:
    path = tmp_path_factory.mktemp("cfg") / "tiny.yaml"
    path.write_text(yaml.safe_dump(TINY), encoding="utf-8")
    return path


@pytest.fixture(scope="module")
def bundle(tmp_path_factory: pytest.TempPathFactory, tiny_config: Path) -> Path:
    return build_release(7, tmp_path_factory.mktemp("rel") / "bundle", tiny_config)


def test_bundle_has_data_licenses_datasheet_and_checksums(bundle: Path) -> None:
    for name in ("manifest.csv", "DATASHEET.md", "LICENSE-DATA.md", "release.json", "SHA256SUMS"):
        assert (bundle / name).is_file(), name
    assert (bundle / "templates" / "philsys-card.json").is_file()
    assert not (bundle / "templates" / "validate.py").exists()

    license_text = (bundle / "LICENSE-DATA.md").read_text(encoding="utf-8")
    assert "CC BY 4.0" in license_text and "AGPL-3.0" in license_text
    assert "SIL Open Font License" in license_text
    datasheet = (bundle / "DATASHEET.md").read_text(encoding="utf-8")
    assert "Identity verification" in datasheet and "fake IDs" in datasheet

    info = json.loads((bundle / "release.json").read_text(encoding="utf-8"))
    assert info["seed"] == 7 and info["data_license"] == "CC BY 4.0"
    sums = (bundle / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    images = sorted(p.relative_to(bundle).as_posix() for p in (bundle / "images").glob("*.png"))
    assert images and set(images) <= {line.split("  ", 1)[1] for line in sums}


def test_a_rebuild_from_the_seed_matches_every_checksum(bundle: Path) -> None:
    assert verify_release(bundle) == []


def test_a_changed_file_is_reported(bundle: Path, tmp_path: Path) -> None:
    import shutil

    copy = tmp_path / "copy"
    shutil.copytree(bundle, copy)
    image = sorted((copy / "images").glob("*.png"))[0]
    Image.open(image).rotate(1).save(image)
    assert verify_release(copy) == [image.relative_to(copy).as_posix()]


def test_every_card_image_carries_a_visible_watermark(bundle: Path) -> None:
    assert check_watermarks(bundle) == []
    labels = [json.loads(p.read_text(encoding="utf-8")) for p in (bundle / "labels").glob("*.json")]
    cards = [lab for lab in labels if not lab.get("is_negative")]
    assert cards and all(lab["watermark_contrast"] >= MIN_WATERMARK_CONTRAST for lab in cards)


def test_a_faint_watermark_fails_the_check(bundle: Path, tmp_path: Path) -> None:
    labels = tmp_path / "labels"
    labels.mkdir()
    (labels / "a.json").write_text(
        json.dumps({"is_negative": False, "watermark_contrast": 3.0}), encoding="utf-8"
    )
    (labels / "b.json").write_text(json.dumps({"is_negative": True}), encoding="utf-8")
    (labels / "c.json").write_text(json.dumps({"is_negative": False}), encoding="utf-8")
    assert check_watermarks(tmp_path) == ["a.json", "c.json"]


@pytest.mark.parametrize("template_id", sorted(get_available_templates()))
def test_the_watermark_shows_on_every_template(template_id: str) -> None:
    template = get_available_templates()[template_id]
    for seed in (1, 2, 3):
        _, label = generate_single_sample(template, seed=seed, composite=False)
        assert label["watermark_contrast"] >= MIN_WATERMARK_CONTRAST


@pytest.mark.parametrize("colour", [(255, 255, 255), (20, 24, 36)])
def test_the_ink_follows_the_card(colour: tuple[int, int, int]) -> None:
    img = Image.new("RGB", (1012, 638), colour)
    assert _draw_watermark(img, 1012, 638) >= MIN_WATERMARK_CONTRAST


def test_a_release_with_photo_negatives_needs_the_curated_photos(tmp_path: Path) -> None:
    cfg = json.loads(json.dumps(TINY))
    cfg["negatives"] = {
        "enabled": True,
        "count_per_type": 1,
        "types": ["photo"],
        "photos_dir": str(tmp_path / "none"),
    }
    path = tmp_path / "photo.yaml"
    path.write_text(yaml.safe_dump(cfg), encoding="utf-8")
    with pytest.raises(RuntimeError, match="fetch_photos"):
        build_release(1, tmp_path / "out", path)
