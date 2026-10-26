"""Pipeline metrics from an on-device batch export (issue #30).

A hand-built export of four images checks every table: detection P/R/F1, AP50, mAP50 and the
false-positive rate on negatives, the confusion matrix, corner error, field recall, field/box
precision, and the inconclusive, retry, fallback and recheck rates. The leak test needs
PaddleOCR and is covered by validation/leaktest.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from validation.report.__main__ import main
from validation.report.pipeline import (
    average_precision,
    is_d7_cohort_item,
    match_fields,
    measure_d7_recheck,
    run_pipeline,
)

SIZE = [400, 300]
CARD = [[50, 50], [350, 50], [350, 250], [50, 250]]
FIELD = [[100, 100], [200, 100], [200, 140], [100, 140]]


def _label(cls: str | None, split: str = "test", condition: str = "none") -> dict:
    negative = cls is None
    return {
        "document_class": "negative" if negative else cls,
        "is_negative": negative,
        "card_corners": [] if negative else CARD,
        "image_size": SIZE,
        "condition": condition,
        "severity": 0,
        "split": split,
        "is_failed_redaction": False,
        "redaction_type": "none",
        "fields": [] if negative else [{"type": "pcn", "sensitive": True, "polygon": FIELD}],
    }


def _result(id_: str, cls: str | None, state: str, boxes: list, **extra: object) -> dict:
    return {
        "id": id_, "state": state, "document_class": cls, "document_confidence": 0.9,
        "card_corners": CARD if cls else None, "retries": 0, "recheck_passed": True,
        "boxes": [{"field_type": t, "confidence": 0.9, "polygon": p} for t, p in boxes],
        **extra,
    }  # fmt: skip


@pytest.fixture
def export(tmp_path: Path) -> tuple[Path, Path]:
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()
    cases = {
        # Correct class, field fully covered, plus one box on nothing sensitive.
        "a": (_label("philsys"), _result("a", "philsys", "REDACTED",
              [("pcn", FIELD), ("name", [[300, 200], [340, 200], [340, 240], [300, 240]])])),
        # Wrong class, half the field covered, retried into the whole-image fallback.
        "b": (_label("lto"), _result("b", "philsys", "NEEDS_CHECK",
              [("pcn", [[100, 100], [150, 100], [150, 140], [100, 140]])], retries=3)),
        # A negative the detector flags.
        "c": (_label(None), _result("c", "payment_card", "NEEDS_CHECK", [])),
        # A validation-split image: never scored.
        "d": (_label("lto", split="val"), _result("d", "lto", "REDACTED", [("pcn", FIELD)])),
    }  # fmt: skip
    for id_, (label, _) in cases.items():
        (labels / f"{id_}.json").write_text(json.dumps(label), encoding="utf-8")
    images = [r for _, r in cases.values()]
    images[1]["boxes"].append({"field_type": "whole_document", "confidence": 1.0,
                               "polygon": [[0, 0], [1, 0], [1, 1], [0, 1]]})  # fmt: skip
    (out / "results.json").write_text(json.dumps({"images": images}), encoding="utf-8")
    return out, labels


def _rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def _find(rows: list[dict[str, str]], **match: str) -> dict[str, str]:
    return next(r for r in rows if all(r.get(k) == v for k, v in match.items()))


def test_detection_counts_and_confusion(export: tuple[Path, Path], tmp_path: Path) -> None:
    written = run_pipeline(*export, tmp_path / "out", leaks=False)
    det = _rows(written["detection"])
    p_philsys = _find(det, metric="precision", **{"class": "philsys", "condition": "ALL"})
    assert p_philsys["count"] == "1"
    assert p_philsys["of"] == "2"
    r_lto = _find(det, metric="recall", **{"class": "lto", "condition": "ALL"})
    assert r_lto["of"] == "1"  # val left out
    assert r_lto["count"] == "0"
    fp = _find(det, metric="false_positive_rate", **{"condition": "ALL"})
    assert (fp["count"], fp["of"], fp["pct"]) == ("1", "1", "100.0")
    assert float(fp["ci_low"]) > 0  # Wilson interval next to the rate

    # Evaluated classes: only philsys and lto have ground-truth positives in test split
    map50 = _find(det, metric="map50", **{"class": "ALL", "condition": "ALL"})
    assert map50["of"] == "2"
    assert map50["count"] == "2"
    assert map50["ci_low"] == ""  # Wilson omitted for non-binomial aggregate

    # Condition breakdown present
    det_none = _find(det, metric="precision", **{"class": "philsys", "condition": "none"})
    assert det_none["count"] == "1"

    confusion = {r["truth"]: r for r in _rows(written["confusion"])}
    assert confusion["lto"]["pred_philsys"] == "1"
    assert confusion["none"]["pred_payment_card"] == "1"


def test_fields_and_pipeline_rates(export: tuple[Path, Path], tmp_path: Path) -> None:
    written = run_pipeline(*export, tmp_path / "out", leaks=False)
    fields = _rows(written["fields"])
    field_precision = _find(
        fields, metric="field_precision", **{"class": "ALL", "condition": "ALL"}
    )
    assert (field_precision["count"], field_precision["of"]) == ("2", "3")
    recall = _find(fields, metric="field_recall", **{"class": "ALL", "condition": "ALL"})
    assert (recall["count"], recall["of"]) == ("2", "2")  # both sensitive fields matched IoU >= 0.5
    coverage = _find(
        fields, metric="coverage_recall", **{"class": "ALL", "condition": "ALL"}
    )
    # only image a's field is covered >= 99.5%
    assert (coverage["count"], coverage["of"]) == ("1", "2")
    field_f1 = _find(fields, metric="field_f1", **{"class": "ALL", "condition": "ALL"})
    assert field_f1["pct"] == "80.0"
    assert (field_f1["count"], field_f1["of"]) == ("2", "2")
    assert (field_f1["tp"], field_f1["fp"], field_f1["fn"]) == ("2", "1", "0")
    precision = _find(fields, metric="box_precision", **{"class": "philsys", "condition": "ALL"})
    assert (precision["count"], precision["of"]) == ("1", "2")
    pipe = _rows(written["pipeline"])
    every = {"class": "ALL", "condition": "ALL"}
    assert _find(pipe, metric="inconclusive", **every)["count"] == "1"
    assert _find(pipe, metric="retried", **every)["count"] == "1"
    assert _find(pipe, metric="whole_image_fallback", **every)["count"] == "1"
    recheck = _rows(written["recheck"])
    assert _find(recheck, metric="recheck_sensitivity", **every)["of"] == "0"
    corners = _rows(written["corners"])
    assert _find(corners, **{"class": "philsys"})["max_pct"] == "0.0"


def test_d7_verification_does_not_change_pipeline_scores(export, tmp_path: Path) -> None:
    out, labels = export
    baseline = run_pipeline(out, labels, tmp_path / "baseline", leaks=False)
    label = {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "partial"}
    (labels / "d7_partial.json").write_text(json.dumps(label), encoding="utf-8")
    (out / "d7_results.json").write_text(json.dumps({"images": [
        {"id": "d7_partial", "recheck_outcome": "failed"},
    ]}), encoding="utf-8")

    mixed = run_pipeline(out, labels, tmp_path / "mixed", leaks=False)
    for table in ("detection", "confusion", "corners", "fields", "pipeline"):
        assert _rows(mixed[table]) == _rows(baseline[table]), table
    sensitivity = _find(_rows(mixed["recheck"]), metric="recheck_sensitivity",
                        **{"class": "ALL", "condition": "ALL"})
    assert (sensitivity["count"], sensitivity["of"]) == ("1", "1")


def test_average_precision() -> None:
    assert average_precision([(0.9, True), (0.8, True)], 2) == 1.0
    assert average_precision([(0.9, False), (0.8, True)], 1) == pytest.approx(0.5)
    assert average_precision([], 3) == 0.0


def test_cli(export: tuple[Path, Path], tmp_path: Path) -> None:
    out_dir, labels = export
    main(
        [
            "pipeline",
            str(out_dir),
            "--labels",
            str(labels),
            "--out",
            str(tmp_path / "r"),
            "--no-leak",
        ]
    )
    assert (tmp_path / "r" / "pipeline.csv").exists()
    assert (tmp_path / "r" / "recheck.csv").exists()


def test_an_export_without_a_label_is_an_error(export: tuple[Path, Path], tmp_path: Path) -> None:
    out_dir, labels = export
    (labels / "a.json").unlink()
    with pytest.raises(FileNotFoundError):
        run_pipeline(out_dir, labels, tmp_path / "out", leaks=False)


def test_leaks_judge_the_exported_copies(export: tuple[Path, Path], tmp_path: Path) -> None:
    from PIL import Image, ImageDraw

    out_dir, labels = export
    for id_, covered in (("a", FIELD), ("b", [[100, 100], [150, 100], [150, 140], [100, 140]])):
        img = Image.new("RGB", tuple(SIZE), "white")
        ImageDraw.Draw(img).polygon([tuple(p) for p in covered], fill="black")
        img.save(out_dir / f"{id_}.jpg", quality=95)

    # A reader that recovers the PCN wherever any white is left in the field.
    def reader(crop):  # noqa: ANN001, ANN202
        return "1234-5678-9012-3456" if crop.convert("L").getextrema()[1] > 200 else ""

    written = run_pipeline(out_dir, labels, tmp_path / "out", reader=reader)
    leaks = _rows(written["leaks"])
    text = _find(leaks, metric="text_leaked", **{"class": "ALL", "condition": "ALL"})
    assert text["of"] == "2"


def test_d7_recheck_measurement_empty_when_no_d7(export: tuple[Path, Path], tmp_path: Path) -> None:
    written = run_pipeline(*export, tmp_path / "out", leaks=False)
    rows = _rows(written["recheck"])
    overall = {"class": "ALL", "condition": "ALL"}
    assert _find(rows, metric="recheck_sensitivity", **overall)["pct"] == "0.0"
    assert _find(rows, metric="recheck_false_negative_rate", **overall)["pct"] == "0.0"
    assert _find(rows, metric="recheck_false_positive_rate", **overall)["pct"] == "0.0"
    assert _find(rows, metric="recheck_inconclusive_rate", **overall)["pct"] == "0.0"


def test_recheck_outcomes_fn_fp_inconclusive_and_skipped(tmp_path: Path) -> None:
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()
    cases = {
        # TP: failed redaction detected as failed
        "tp": (
            {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "partial"},
            _result("tp", "philsys", "NEEDS_CHECK", [("pcn", FIELD)], recheck_outcome="failed"),
        ),
        # FN: failed redaction missed (clean outcome)
        "fn": (
            {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "partial"},
            _result("fn", "philsys", "REDACTED", [("pcn", FIELD)], recheck_outcome="clean"),
        ),
        # FP: complete redaction falsely flagged as failed
        "fp": (
            {**_label("philsys"), "is_failed_redaction": False, "redaction_type": "complete"},
            _result("fp", "philsys", "NEEDS_CHECK", [("pcn", FIELD)], recheck_outcome="failed"),
        ),
        # TN: complete redaction correctly scored clean
        "tn": (
            {**_label("philsys"), "is_failed_redaction": False, "redaction_type": "complete"},
            _result("tn", "philsys", "REDACTED", [("pcn", FIELD)], recheck_outcome="clean"),
        ),
        # Inconclusive: failed redaction but outcome is inconclusive
        "inc": (
            {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "partial"},
            _result(
                "inc", "philsys", "NEEDS_CHECK", [("pcn", FIELD)], recheck_outcome="inconclusive"
            ),
        ),
        # Skipped: unrun recheck (e.g. detector failed, state FAILED)
        "skip": (
            {**_label("philsys"), "is_failed_redaction": False, "redaction_type": "complete"},
            _result("skip", None, "FAILED", [], recheck_outcome="skipped"),
        ),
    }
    for id_, (label, _) in cases.items():
        (labels / f"{id_}.json").write_text(json.dumps(label), encoding="utf-8")
    (out / "results.json").write_text(
        json.dumps({"images": [result for _, result in cases.values()]}),
        encoding="utf-8",
    )
    written = run_pipeline(out, labels, tmp_path / "out", leaks=False)
    rows = _rows(written["recheck"])
    overall = {"class": "ALL", "condition": "ALL"}

    sens = _find(rows, metric="recheck_sensitivity", **overall)
    assert (sens["count"], sens["of"], sens["pct"]) == ("1", "2", "50.0")

    fnr = _find(rows, metric="recheck_false_negative_rate", **overall)
    assert (fnr["count"], fnr["of"], fnr["pct"]) == ("1", "2", "50.0")

    fpr = _find(rows, metric="recheck_false_positive_rate", **overall)
    assert (fpr["count"], fpr["of"], fpr["pct"]) == ("1", "2", "50.0")

    # Skipped excluded from decision-quality denominator (5 evaluated, 1 inconclusive)
    inc = _find(rows, metric="recheck_inconclusive_rate", **overall)
    assert (inc["count"], inc["of"], inc["pct"]) == ("1", "5", "20.0")

    # Skipped reported separately and export coverage preserved
    skp = _find(rows, metric="recheck_skipped_rate", **overall)
    assert (skp["count"], skp["of"], skp["pct"]) == ("1", "6", "16.67")

    cov = _find(rows, metric="recheck_export_coverage", **overall)
    assert (cov["count"], cov["of"], cov["pct"]) == ("5", "6", "83.33")


def test_direct_d7_recheck_measurement() -> None:
    from PIL import Image, ImageDraw

    # 1. Complete solid redaction: passes
    img_clean = Image.new("RGB", tuple(SIZE), "white")
    ImageDraw.Draw(img_clean).polygon([tuple(p) for p in FIELD], fill="black")
    label_clean = {
        **_label("philsys"),
        "is_failed_redaction": False,
        "redaction_type": "complete",
        "redaction": {"target_field_types": ["pcn"], "redaction_boxes": [FIELD]},
    }
    assert measure_d7_recheck(img_clean, label_clean) == "clean"

    # 2. Ineffective non-solid redaction: fails pixel check
    img_ineffective = Image.new("RGB", tuple(SIZE), "white")
    ImageDraw.Draw(img_ineffective).polygon([tuple(p) for p in FIELD], fill=(128, 128, 128))
    label_ineffective = {
        **_label("philsys"),
        "is_failed_redaction": True,
        "redaction_type": "ineffective",
        "redaction": {"target_field_types": ["pcn"], "redaction_boxes": [FIELD]},
    }
    assert measure_d7_recheck(img_ineffective, label_ineffective) == "failed"

    # 3. Partial redaction leaving sensitive text exposed: fails
    img_partial = Image.new("RGB", tuple(SIZE), "white")
    half_box = [[100, 100], [150, 100], [150, 140], [100, 140]]
    ImageDraw.Draw(img_partial).polygon([tuple(p) for p in half_box], fill="black")
    label_partial = {
        **_label("philsys"),
        "is_failed_redaction": True,
        "redaction_type": "partial",
        "redaction": {"target_field_types": ["pcn"], "redaction_boxes": [half_box]},
    }
    assert measure_d7_recheck(img_partial, label_partial) == "failed"


def test_d7_cohort_excludes_ordinary_images() -> None:
    """Ordinary generated images (is_failed_redaction=False) must be excluded."""
    ordinary_label = {
        "document_class": "philsys",
        "is_failed_redaction": False,
        "redaction_type": "none",
    }
    assert not is_d7_cohort_item(ordinary_label)

    complete_label = {
        "document_class": "philsys",
        "is_failed_redaction": False,
        "redaction_type": "complete",
    }
    assert is_d7_cohort_item(complete_label)

    failed_label = {
        "document_class": "philsys",
        "is_failed_redaction": True,
        "redaction_type": "partial",
    }
    assert is_d7_cohort_item(failed_label)


def test_d7_on_device_verification_export_integration(tmp_path: Path) -> None:
    """Integration test: on-device D7 export schema scored directly against desktop labels."""
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()

    d7_items = [
        # Complete D7 redaction: on-device recheck passes -> clean (TN)
        (
            {**_label("philsys"), "is_failed_redaction": False, "redaction_type": "complete"},
            {"id": "d7_clean", "recheck_outcome": "clean", "recheck_passed": True},
        ),
        # Partial D7 redaction: on-device recheck catches failure -> failed (TP)
        (
            {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "partial"},
            {"id": "d7_partial", "recheck_outcome": "failed", "recheck_passed": False},
        ),
        # Ineffective D7 redaction: on-device recheck catches non-solid fill -> failed (TP)
        (
            {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "ineffective"},
            {"id": "d7_ineffective", "recheck_outcome": "failed", "recheck_passed": False},
        ),
        # Misplaced D7 redaction: on-device recheck missed failure -> clean (FN)
        (
            {**_label("philsys"), "is_failed_redaction": True, "redaction_type": "misplaced"},
            {"id": "d7_misplaced", "recheck_outcome": "clean", "recheck_passed": True},
        ),
    ]

    for label, result in d7_items:
        (labels / f"{result['id']}.json").write_text(json.dumps(label), encoding="utf-8")

    # Exported schema from on-device BatchExportTest.exportD7VerificationResults
    d7_export = {
        "device": "Google Pixel 8 (API 34)",
        "images": [result for _, result in d7_items],
    }
    (out / "d7_results.json").write_text(json.dumps(d7_export), encoding="utf-8")
    (out / "results.json").write_text(json.dumps({"images": []}), encoding="utf-8")

    written = run_pipeline(out, labels, tmp_path / "out", leaks=False)
    rows = _rows(written["recheck"])
    overall = {"class": "ALL", "condition": "ALL"}

    sens = _find(rows, metric="recheck_sensitivity", **overall)
    # 3 actual failed redactions, 2 caught (partial, ineffective), 1 missed (misplaced)
    assert (sens["count"], sens["of"], sens["pct"]) == ("2", "3", "66.67")

    fnr = _find(rows, metric="recheck_false_negative_rate", **overall)
    assert (fnr["count"], fnr["of"], fnr["pct"]) == ("1", "3", "33.33")

    fpr = _find(rows, metric="recheck_false_positive_rate", **overall)
    assert (fpr["count"], fpr["of"], fpr["pct"]) == ("0", "1", "0.0")


def test_match_fields_type_and_geometry_and_max_cardinality() -> None:
    """match_fields requires matching field types, geometry IoU >= 0.5, and maximizes matches."""
    size = (400, 300)
    pcn_poly = [[100, 100], [200, 100], [200, 140], [100, 140]]
    overlap_poly = [[100, 100], [190, 100], [190, 140], [100, 140]]  # IoU > 0.5 with pcn_poly

    # 1. Wrong field type: no match even with high IoU
    pred_wrong = [{"field_type": "name", "polygon": overlap_poly}]
    sens = [{"type": "pcn", "sensitive": True, "polygon": pcn_poly}]
    p_cnt, s_cnt = match_fields(pred_wrong, sens, size)
    assert (p_cnt, s_cnt) == (0, 0)

    # 2. Correct field type and geometry: matches
    pred_right = [{"field_type": "pcn", "polygon": overlap_poly}]
    p_cnt, s_cnt = match_fields(pred_right, sens, size)
    assert (p_cnt, s_cnt) == (1, 1)

    # 3. Maximum-cardinality matching:
    # Pred 0 can match Sens 0 and Sens 1. Pred 1 can only match Sens 1.
    # Greedy might pick (0, 1), leaving Pred 1 unmatched (size 1).
    # Maximum cardinality picks (0, 0) and (1, 1) (size 2).
    poly_a = [[10, 10], [50, 10], [50, 50], [10, 50]]
    poly_b = [[60, 10], [100, 10], [100, 50], [60, 50]]
    pred_bipartite = [
        {"field_type": "pcn", "polygon": poly_a},  # matches Sens 0 (poly_a) and Sens 1 (poly_a)
        {"field_type": "pcn", "polygon": poly_b},  # matches Sens 1 (poly_b)
    ]
    sens_bipartite = [
        {"type": "pcn", "polygon": poly_a},
        {"type": "pcn", "polygon": poly_b},
    ]
    p_cnt, s_cnt = match_fields(pred_bipartite, sens_bipartite, size)
    assert (p_cnt, s_cnt) == (2, 2)


def test_realistic_full_image_fallback_fixture(tmp_path: Path) -> None:
    """Full-document fallback covers fields; excluded from instance P/R/F1."""
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()

    # Image with 1 sensitive field and a whole_document fallback box covering the entire card
    whole_box = [[0, 0], [400, 0], [400, 300], [0, 300]]
    label = _label("philsys")
    result = _result("fallback_sample", "philsys", "NEEDS_CHECK", [("whole_document", whole_box)])

    (labels / "fallback_sample.json").write_text(json.dumps(label), encoding="utf-8")
    (out / "results.json").write_text(json.dumps({"images": [result]}), encoding="utf-8")

    written = run_pipeline(out, labels, tmp_path / "out", leaks=False)
    fields = _rows(written["fields"])
    overall = {"class": "ALL", "condition": "ALL"}

    # Instance detection: whole_document is excluded -> 0 predicted field instances
    prec = _find(fields, metric="field_precision", **overall)
    assert (prec["count"], prec["of"]) == ("0", "0")
    rec = _find(fields, metric="field_recall", **overall)
    assert (rec["count"], rec["of"]) == ("0", "1")

    # Coverage recall: whole_document fallback covers the sensitive field
    cov = _find(fields, metric="coverage_recall", **overall)
    assert (cov["count"], cov["of"], cov["pct"]) == ("1", "1", "100.0")

    # Over-coverage: large over-coverage because entire card is redacted
    over = _find(fields, metric="over_coverage_median", **overall)
    assert float(over["pct"]) > 0


def test_map50_support_fewer_than_three_classes(tmp_path: Path) -> None:
    """mAP50 support equals the evaluated class count when fewer than 3 classes are evaluated."""
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()

    # Only 1 class evaluated (philsys)
    label = _label("philsys")
    result = _result("only_one", "philsys", "REDACTED", [("pcn", FIELD)])

    (labels / "only_one.json").write_text(json.dumps(label), encoding="utf-8")
    (out / "results.json").write_text(json.dumps({"images": [result]}), encoding="utf-8")

    written = run_pipeline(out, labels, tmp_path / "out", leaks=False)
    det = _rows(written["detection"])

    map50 = _find(det, metric="map50", **{"class": "ALL", "condition": "ALL"})
    assert map50["of"] == "1"
    assert map50["count"] == "1"
    assert map50["pct"] == "100.0"


def test_detection_per_condition_breakdown(tmp_path: Path) -> None:
    """Detection precision, recall, F1, AP50, and FPR are computed per condition."""
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()

    # 1 clean condition, 1 blur condition
    clean_label = _label("philsys", condition="none")
    clean_res = _result("clean_1", "philsys", "REDACTED", [("pcn", FIELD)])

    blur_label = _label("philsys", condition="blur")
    blur_res = _result("blur_1", "philsys", "REDACTED", [("pcn", FIELD)])

    neg_label = _label(None, condition="blur")
    neg_res = _result("neg_blur", "philsys", "NEEDS_CHECK", [])

    for l_obj, r_obj in [(clean_label, clean_res), (blur_label, blur_res), (neg_label, neg_res)]:
        (labels / f"{r_obj['id']}.json").write_text(json.dumps(l_obj), encoding="utf-8")

    (out / "results.json").write_text(
        json.dumps({"images": [clean_res, blur_res, neg_res]}), encoding="utf-8"
    )

    written = run_pipeline(out, labels, tmp_path / "out", leaks=False)
    det = _rows(written["detection"])

    # Overall and per-condition rows present
    p_all = _find(det, metric="precision", **{"class": "philsys", "condition": "ALL"})
    p_none = _find(det, metric="precision", **{"class": "philsys", "condition": "none"})
    p_blur = _find(det, metric="precision", **{"class": "philsys", "condition": "blur"})

    assert p_all["count"] == "2"
    assert p_none["count"] == "1"
    assert p_blur["count"] == "1"

    fpr_blur = _find(det, metric="false_positive_rate", **{"condition": "blur"})
    assert fpr_blur["count"] == "1"
    assert fpr_blur["of"] == "1"
