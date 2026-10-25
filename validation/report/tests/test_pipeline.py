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
from validation.report.pipeline import average_precision, run_pipeline

SIZE = [400, 300]
CARD = [[50, 50], [350, 50], [350, 250], [50, 250]]
FIELD = [[100, 100], [200, 100], [200, 140], [100, 140]]


def _label(cls: str | None, split: str = "test") -> dict:
    negative = cls is None
    return {
        "document_class": "negative" if negative else cls,
        "is_negative": negative,
        "card_corners": [] if negative else CARD,
        "image_size": SIZE,
        "condition": "none",
        "severity": 0,
        "split": split,
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
    assert _find(det, metric="precision", **{"class": "philsys"})["count"] == "1"
    assert _find(det, metric="precision", **{"class": "philsys"})["of"] == "2"
    assert _find(det, metric="recall", **{"class": "lto"})["of"] == "1"  # val image left out
    assert _find(det, metric="recall", **{"class": "lto"})["count"] == "0"
    fp = _find(det, metric="false_positive_rate")
    assert (fp["count"], fp["of"], fp["pct"]) == ("1", "1", "100.0")
    assert float(fp["ci_low"]) > 0  # Wilson interval next to the rate
    map50 = _find(det, metric="map50", **{"class": "ALL"})
    assert map50["of"] == "3"
    assert map50["count"] == ""
    assert map50["ci_low"] == ""
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
    assert field_f1["count"] == ""
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


def test_recheck_sensitivity_on_failed_redaction_cases(tmp_path: Path) -> None:
    out, labels = tmp_path / "export", tmp_path / "labels"
    out.mkdir()
    labels.mkdir()
    cases = {
        "ok": (
            {
                **_label("philsys"),
                "is_failed_redaction": False,
                "redaction_type": "complete",
                "redaction": {"target_field_types": ["pcn"], "redaction_boxes": [FIELD]},
            },
            _result("ok", "philsys", "REDACTED", [("pcn", FIELD)], retries=0, recheck_passed=True),
        ),
        "bad": (
            {
                **_label("philsys"),
                "is_failed_redaction": True,
                "redaction_type": "partial",
                "redaction": {"target_field_types": ["pcn"], "redaction_boxes": [FIELD]},
            },
            _result(
                "bad", "philsys", "NEEDS_CHECK", [("pcn", FIELD)], retries=2, recheck_passed=False
            ),
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
    assert _find(rows, metric="recheck_sensitivity", **overall)["pct"] == "100.0"
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

    inc = _find(rows, metric="recheck_inconclusive_rate", **overall)
    assert (inc["count"], inc["of"], inc["pct"]) == ("2", "6", "33.33")


def test_direct_d7_recheck_measurement() -> None:
    from PIL import Image, ImageDraw

    from validation.report.pipeline import measure_d7_recheck

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

    # 2. Ineffective non-solid redaction: fails
    img_ineffective = Image.new("RGB", tuple(SIZE), "white")
    # Draw gray non-solid fill (R, G, B > 8)
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
