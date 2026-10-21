"""Pytest test suite for failed-redaction generator.

Validates Issue #7 acceptance criteria:
1. 'complete': 100% solid-fill opaque mask covering target fields (expected leak: none).
2. 'partial': Redaction box covering 30%–70% of field (expected leak: partial_text_exposed).
3. 'misplaced': Offset box shifted away from intended field (expected leak: offset_uncovered_field).
4. 'ineffective': Semi-transparent fill or blur (expected leak: transparent_or_blur_fill_legible).
5. Ground truth labels record expected leak, covered fraction, and target field types.
"""

from __future__ import annotations

import pytest
from PIL import Image

from ml.failed_redactions import (
    FAILED_REDACTION_TYPES,
    apply_failed_redaction,
)
from ml.generate import generate_single_sample, get_available_templates


@pytest.fixture(scope="session")
def sample_philsys_card() -> tuple[Image.Image, dict]:
    """Generate a clean test card sample to apply redactions to."""
    templates = get_available_templates()
    tmpl = templates["philsys-card"]
    return generate_single_sample(tmpl, seed=1001, composite=False)


def test_complete_redaction_covers_all_target_area(
    sample_philsys_card: tuple[Image.Image, dict],
) -> None:
    """Verify complete redaction applies 100% solid coverage with no expected leak."""
    img, label = sample_philsys_card
    redacted_img, red_label = apply_failed_redaction(
        img,
        label,
        redaction_type="complete",
        seed=42,
    )

    assert isinstance(redacted_img, Image.Image)
    assert red_label["is_failed_redaction"] is False
    assert red_label["redaction_type"] == "complete"
    assert red_label["expected_leak"] == "none"

    red_meta = red_label["redaction"]
    assert red_meta["covered_fraction"] == 1.0
    assert red_meta["leak_fraction"] == 0.0
    assert red_meta["residual_detectability"] == "none"
    assert len(red_meta["redaction_boxes"]) > 0


def test_partial_redaction_covers_between_30_and_70_percent(
    sample_philsys_card: tuple[Image.Image, dict],
) -> None:
    """Verify partial redaction covers 30%–70% of field and labels expected leak."""
    img, label = sample_philsys_card

    for seed in range(5):
        redacted_img, red_label = apply_failed_redaction(
            img,
            label,
            redaction_type="partial",
            seed=seed,
        )

        assert red_label["is_failed_redaction"] is True
        assert red_label["redaction_type"] == "partial"
        assert red_label["expected_leak"] == "partial_text_exposed"

        red_meta = red_label["redaction"]
        assert 0.30 <= red_meta["covered_fraction"] <= 0.70
        assert 0.30 <= red_meta["leak_fraction"] <= 0.70
        assert red_meta["residual_detectability"] == "partial"


def test_misplaced_redaction_offsets_box(sample_philsys_card: tuple[Image.Image, dict]) -> None:
    """Verify misplaced redaction offsets the box away from the field leaving it exposed."""
    img, label = sample_philsys_card
    redacted_img, red_label = apply_failed_redaction(
        img,
        label,
        redaction_type="misplaced",
        seed=88,
    )

    assert red_label["is_failed_redaction"] is True
    assert red_label["redaction_type"] == "misplaced"
    assert red_label["expected_leak"] == "offset_uncovered_field"

    red_meta = red_label["redaction"]
    assert red_meta["covered_fraction"] == 0.0
    assert red_meta["leak_fraction"] == 1.0
    assert red_meta["residual_detectability"] == "full"


def test_ineffective_redaction_maintains_detectability(
    sample_philsys_card: tuple[Image.Image, dict],
) -> None:
    """Verify ineffective redaction applies semi-transparent or blur fill."""
    img, label = sample_philsys_card
    redacted_img, red_label = apply_failed_redaction(
        img,
        label,
        redaction_type="ineffective",
        seed=123,
    )

    assert red_label["is_failed_redaction"] is True
    assert red_label["redaction_type"] == "ineffective"
    assert red_label["expected_leak"] == "transparent_or_blur_fill_legible"

    red_meta = red_label["redaction"]
    assert red_meta["covered_fraction"] == 1.0
    assert red_meta["leak_fraction"] == 1.0
    assert red_meta["residual_detectability"] == "high"


@pytest.mark.parametrize("r_type", FAILED_REDACTION_TYPES)
def test_all_redaction_types_preserve_base_image_id(
    r_type: str,
    sample_philsys_card: tuple[Image.Image, dict],
) -> None:
    """Verify failed redactions preserve base_image_id so split isolation remains intact."""
    img, label = sample_philsys_card
    label_copy = {**label, "base_image_id": "test_card_base_0001"}

    _, red_label = apply_failed_redaction(img, label_copy, redaction_type=r_type, seed=42)
    assert red_label["base_image_id"] == "test_card_base_0001"
