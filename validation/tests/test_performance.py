import json

import pytest

from validation.performance import distribution, main, summarize


def image(id_, total, state="NOT_DETECTED", cls=None):
    return {
        "id": id_,
        "state": state,
        "document_class": cls,
        "timings_ms": {"total": total, "detect": 10},
        "peak_memory_mb": 80,
    }


def test_known_percentiles_and_cohort_targets():
    payload = {
        "images": [image("a", 100), image("b", 200), image("c", 1200, "REDACTED", "philsys")]
    }
    report = summarize(payload, "phone", 4096)
    targets = {t["cohort"]: t for t in report["latency_targets"]}
    assert targets["non_document"]["median_ms"] == 150
    assert targets["non_document"]["status"] == "within_target"
    assert targets["flagged"]["median_ms"] == 1200
    assert distribution([100, 200])["p90"] == 190
    decode = next(r for r in report["stages"] if r["cohort"] == "all" and r["stage"] == "decode")
    assert decode["count"] == 0 and decode["median_ms"] is None


@pytest.mark.parametrize("bad", [None, True, -1, float("nan"), float("inf"), "10"])
def test_invalid_timings_are_missing_not_fast_observations(bad):
    report = summarize({"images": [image("a", 100), image("b", bad)]}, "phone")
    assert report["latency_targets"][0]["status"] == "incomplete"
    assert report["latency_targets"][0]["count"] == 1


def test_emulator_never_proves_phone_target():
    report = summarize({"images": [image("a", 1)]}, "emulator")
    assert report["latency_targets"][0]["status"] == "diagnostic_only"
    assert report["latency_targets"][1]["status"] == "not_measured"


def test_slow_flagged_images_exceed_target():
    report = summarize({"images": [image("a", 2000, "NEEDS_CHECK", "lto")]}, "phone")
    assert report["latency_targets"][0]["status"] == "not_measured"
    assert report["latency_targets"][1]["status"] == "over_target"


def test_empty_export_is_rejected():
    with pytest.raises(ValueError, match="nonempty"):
        summarize({"images": []}, "phone")


def test_failed_or_unfinished_images_prevent_acceptance():
    report = summarize({"images": [image("a", 1), image("b", 1, "FAILED")]}, "phone")
    assert report["unclassified_or_unfinished"] == 1
    assert report["latency_targets"][0]["status"] == "incomplete"


def test_duplicate_ids_rejected():
    with pytest.raises(ValueError, match="Duplicate"):
        summarize({"images": [image("a", 1), image("a", 1)]}, "phone")


def test_cli_writes_aggregate_artifacts_without_image_ids(tmp_path):
    source = tmp_path / "results.json"
    source.write_text(
        json.dumps({"device": "Test phone", "images": [image("private-filename", 100)]})
    )
    out = tmp_path / "report"
    main([str(source), "--device-kind", "phone", "--out", str(out)])
    report = json.loads((out / "summary.json").read_text())
    assert len(report["source_sha256"]) == 64
    assert (out / "stages.csv").exists()
    assert "private-filename" not in (out / "summary.json").read_text()
