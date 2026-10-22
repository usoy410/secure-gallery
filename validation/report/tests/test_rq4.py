"""Tests for the RQ4 report (issue #14)."""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from validation.leaktest.core import FieldResult
from validation.report.rq4 import (
    ImageOutcome,
    aggregate,
    choose_pad,
    covered_share,
    field_leaks,
    over_redaction,
    run_rq4,
)
from validation.report.stats import wilson_interval


def test_wilson_interval() -> None:
    assert wilson_interval(0, 0) == (0.0, 0.0)
    low, high = wilson_interval(0, 10)
    assert low == 0.0 and high == pytest.approx(0.2775, abs=1e-4)
    assert wilson_interval(5, 10) == pytest.approx((0.2366, 0.7634), abs=1e-4)
    assert wilson_interval(10, 10) == pytest.approx((0.7225, 1.0), abs=1e-4)


def test_over_redaction_counts_only_non_sensitive_card_area() -> None:
    label = {
        "card_corners": [[0, 0], [100, 0], [100, 100], [0, 100]],
        "fields": [
            {"sensitive": True, "polygon": [[0, 0], [50, 0], [50, 100], [0, 100]]},
            {"sensitive": False, "polygon": [[60, 0], [100, 0], [100, 20], [60, 20]]},
        ],
    }
    left = [[0, 0], [50, 0], [50, 100], [0, 100]]
    whole = [[0, 0], [100, 0], [100, 100], [0, 100]]
    assert over_redaction(label, [left], (120, 120)) == pytest.approx(0.0, abs=0.03)
    assert over_redaction(label, [whole], (120, 120)) == pytest.approx(1.0)
    assert over_redaction(label, [], (120, 120)) == 0.0
    assert over_redaction({"card_corners": [], "fields": []}, [whole], (120, 120)) is None


def test_covered_share_of_a_picture_field() -> None:
    face = [[0, 0], [100, 0], [100, 100], [0, 100]]
    shifted_box = [[10, 0], [110, 0], [110, 100], [10, 100]]  # misses a 10 px strip
    assert covered_share(face, [shifted_box], (120, 120)) == pytest.approx(0.9, abs=0.02)
    assert covered_share(face, [], (120, 120)) == 0.0


def _r(field_type: str, channel: str, leaked: bool, judged: bool = True) -> FieldResult:
    return FieldResult("img", "philsys", "philsys-card", "none", field_type, field_type, "file",
                       channel, judged, leaked, 0.0, "x")  # fmt: skip


def test_rq4_leak_rule() -> None:
    rows = {r["field_type"]: r for r in field_leaks([
        # Text field with only part of its area uncovered, nothing readable: not a leak,
        # but it is one under the strict reading.
        _r("pcn", "visible_pixels", False), _r("pcn", "opacity", True),
        # Text field read back: a leak.
        _r("full_name", "visible_pixels", True), _r("full_name", "opacity", True),
        # Uncovered face: a leak through opacity, its only channel.
        _r("face_photo", "visible_pixels", False, judged=False), _r("face_photo", "opacity", True),
        # Mosaic over text is a risk flag, never a leak.
        _r("address", "mosaic_blur", True), _r("address", "opacity", False),
        # File-level metadata is not a field.
        _r("image", "metadata", True),
    ])}  # fmt: skip
    assert set(rows) == {"pcn", "full_name", "face_photo", "address"}
    assert (rows["pcn"]["leaked"], rows["pcn"]["strict"]) == (False, True)
    assert rows["full_name"]["leaked"] and rows["face_photo"]["leaked"]
    assert not rows["address"]["leaked"] and not rows["address"]["strict"]
    assert rows["full_name"]["channels"] == "opacity;visible_pixels"
    assert rows["face_photo"]["kind"] == "picture" and rows["pcn"]["kind"] == "text"


def _outcome(strategy: str, condition: str, severity: int, leaked: int, **kw) -> ImageOutcome:
    return ImageOutcome(
        "test", strategy, 4, f"{condition}{severity}{leaked}", "philsys", condition, severity,
        text_fields=5, text_leaked=leaked, text_strict=kw.get("strict", leaked),
        picture_fields=2, picture_exposed=kw.get("exposed", 0),
        picture_covered=kw.get("covered", [1.0, 1.0]),
        fallback=kw.get("fallback", False), over_redacted=kw.get("over"),
    )  # fmt: skip


def test_aggregate_per_cell_and_overall() -> None:
    rows = aggregate([
        _outcome("hybrid", "none", 0, 0, over=0.1),
        _outcome("hybrid", "blur", 3, 2, over=0.3, fallback=True, strict=4, exposed=2,
                 covered=[0.92, 0.95]),
        _outcome("layout_only", "blur", 3, 5),
    ])  # fmt: skip
    cell = {(r["strategy"], r["condition"], r["severity"]): r for r in rows}
    blur = cell[("hybrid", "blur", "3")]
    assert (blur["text_fields"], blur["text_leaked"], blur["leak_rate"]) == (5, 2, 0.4)
    assert blur["strict_leak_rate"] == 0.8 and blur["fallback_rate"] == 1.0
    assert (blur["picture_exposed"], blur["picture_exposed_rate"]) == (2, 1.0)
    assert blur["picture_median_covered_pct"] == 95.0
    overall = cell[("hybrid", "ALL", "ALL")]
    assert (overall["images"], overall["text_leaked"], overall["images_with_leak"]) == (2, 2, 1)
    assert overall["picture_exposed_rate"] == 0.5
    assert overall["over_redacted_pct"] == 20.0
    assert overall["leak_ci_low"] < overall["leak_rate"] < overall["leak_ci_high"]
    assert cell[("layout_only", "blur", "3")]["over_redacted_pct"] == ""
    # Rows follow the localizer's strategy order: ocr_first, layout_only, hybrid.
    assert [r["strategy"] for r in rows][0] == "layout_only"
    assert [r["strategy"] for r in rows][-1] == "hybrid"


def test_choose_pad_prefers_fewer_leaks_then_less_over_redaction() -> None:
    tuning = [
        {"pad": 2, "leak_rate": 0.2, "over_redacted_pct": 1.0},
        {"pad": 8, "leak_rate": 0.1, "over_redacted_pct": 3.0},
        {"pad": 12, "leak_rate": 0.1, "over_redacted_pct": 5.0},
    ]
    assert choose_pad(tuning) == 8
    assert choose_pad([]) == 4  # nothing to tune on: the localizer default


def test_end_to_end_on_a_small_benchmark(tmp_path: Path) -> None:
    config = tmp_path / "tiny.yaml"
    config.write_text(
        "dataset: {name: tiny, out_dir: data/tiny, seed: 3, "
        "splits: {train: 0.34, val: 0.33, test: 0.33}}\n"
        "cards: {variants: [lto-dl-current], count_per_variant: 3, composite: true, "
        "jitter: false, conditions: 'blur:1'}\n"
        "negatives: {enabled: false}\nfailed_redactions: {enabled: false}\n",
        encoding="utf-8",
    )
    data, out = tmp_path / "bench", tmp_path / "report"
    result = run_rq4(data, out, config, reader=lambda crop: "", pad_grid=(2, 6),
                     progress=lambda msg: None)  # fmt: skip

    assert result["pad"] in (2, 6)
    assert result["val_images"] == 2 and result["test_images"] == 2
    for name in ("rq4_results.csv", "rq4_fields.csv", "tuning.csv", "README.md",
                 "leak_rate_blur.png"):  # fmt: skip
        assert (out / name).exists(), name

    with (data / "manifest.csv").open(encoding="utf-8") as fh:
        test_ids = {Path(r["image_path"]).stem for r in csv.DictReader(fh) if r["split"] == "test"}
    with (out / "rq4_fields.csv").open(encoding="utf-8") as fh:
        fields = list(csv.DictReader(fh))
    assert fields and {r["image_id"] for r in fields} <= test_ids  # test split only
    assert {r["strategy"] for r in fields} == {"ocr_first", "layout_only", "hybrid"}

    with (out / "rq4_results.csv").open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    overall = {r["strategy"]: r for r in rows if r["condition"] == "ALL"}
    assert set(overall) == {"ocr_first", "layout_only", "hybrid"}
    assert float(overall["layout_only"]["fallback_rate"]) == 0.0  # ground-truth corners

    readme = (out / "README.md").read_text(encoding="utf-8")
    assert f"OCR box padding = **{result['pad']} px**" in readme
    assert json.loads((out / "summary.json").read_text(encoding="utf-8"))["pad"] == result["pad"]
