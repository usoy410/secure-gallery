"""Pipeline metrics from the app's on-device batch export (#30; RQ2, RQ3, RQ5, RQ6 in §6, §9.1).

Reads ``results.json`` and the redacted copies written by ``scripts/batch_export.sh`` (#29),
pairs every image with its ground-truth label, and writes one table per measure:

- ``detection.csv``: per class precision, recall, F1 and AP50, plus the false-positive rate on
  non-ID images. A detection is correct when the class matches and the detected card overlaps
  the true card with IoU >= 0.5. ``confusion.csv`` holds the class confusion matrix.
- ``corners.csv``: corner error of correct detections, as a percent of the card width.
- ``fields.csv``: field-level recall (a sensitive field fully covered, >= 99.5% of its area),
  box precision (a box that touches a sensitive field), coverage IoU and over-coverage.
- ``pipeline.csv``: inconclusive (NEEDS_CHECK), retry, whole-image fallback and recheck-pass
  rates.
- ``leaks.csv``: the D11-D13 leak test on the exported copies, by the RQ4 rule.

Every rate comes with its counts and a 95% Wilson interval, per class and condition, plus
``ALL`` rows. Only test-split images are scored.

Not covered here: recheck sensitivity on the D7 failed-redaction cases, which needs an export
mode that rechecks already-redacted images instead of redacting them again.
"""

from __future__ import annotations

import csv
import json
import statistics
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from PIL import ImageChops

from validation.report.rq4 import _mask, covered_share, field_leaks, over_redaction
from validation.report.stats import wilson_interval

CLASSES = ("philsys", "lto", "payment_card")
FULLY_COVERED = 0.995  # the §8.5 / D11 99.5% rule
MATCH_IOU = 0.5


def load(export_dir: Path, labels_dir: Path, split: str = "test") -> list[tuple[dict, dict]]:
    """(result, label) pairs for every exported image of ``split``."""
    results = json.loads((export_dir / "results.json").read_text(encoding="utf-8"))["images"]
    labels = {p.stem: p for p in labels_dir.rglob("*.json") if not p.name.endswith(".boxes.json")}
    pairs = []
    for result in results:
        if result["id"] not in labels:
            raise FileNotFoundError(f"No label for exported image {result['id']!r}")
        label = json.loads(labels[result["id"]].read_text(encoding="utf-8"))
        if label.get("split", split) == split:
            pairs.append((result, label))
    return pairs


def truth_class(label: dict) -> str | None:
    if label.get("is_negative") or label.get("document_class") in (None, "negative"):
        return None
    return label["document_class"]


def condition(label: dict) -> str:
    cond = label.get("condition") or "none"
    return f"{cond}:{label['severity']}" if label.get("severity") not in (None, "", 0) else cond


def polygon_iou(a: list, b: list, size: tuple[int, int]) -> float:
    ma, mb = _mask(size, [a]), _mask(size, [b])
    inter = ImageChops.multiply(ma, mb).histogram()[255]
    union = ImageChops.lighter(ma, mb).histogram()[255]
    return inter / union if union else 0.0


def correct(result: dict, label: dict) -> bool:
    """Right class and a card overlapping the true card with IoU >= MATCH_IOU."""
    truth = truth_class(label)
    if truth is None or result.get("document_class") != truth or not result.get("card_corners"):
        return False
    size = tuple(label["image_size"])
    return polygon_iou(result["card_corners"], label["card_corners"], size) >= MATCH_IOU


def average_precision(scored: list[tuple[float, bool]], positives: int) -> float:
    """All-point interpolated AP from (confidence, correct) detections of one class."""
    if positives == 0:
        return 0.0
    tp = fp = 0
    points = []
    for _, ok in sorted(scored, key=lambda s: -s[0]):
        tp, fp = tp + ok, fp + (not ok)
        points.append((tp / positives, tp / (tp + fp)))
    ap, prev_recall = 0.0, 0.0
    for i, (recall, _) in enumerate(points):
        best = max(p for _, p in points[i:])
        ap += (recall - prev_recall) * best
        prev_recall = recall
    return ap


def rate(metric: str, cls: str, cond: str, hits: int, total: int) -> dict[str, Any]:
    low, high = wilson_interval(hits, total) if total else (0.0, 0.0)
    return {
        "metric": metric, "class": cls, "condition": cond, "count": hits, "of": total,
        "pct": round(100 * hits / total, 2) if total else 0.0,
        "ci_low": round(100 * low, 2), "ci_high": round(100 * high, 2),
    }  # fmt: skip


def grouped(pairs: Iterable[tuple[dict, dict]]) -> dict[tuple[str, str], list[tuple[dict, dict]]]:
    """ID images by (class, condition), with ALL rows for each dimension. Non-ID images are
    left out: field, pipeline and leak rates are about IDs and cards."""
    groups: dict[tuple[str, str], list[tuple[dict, dict]]] = {}
    for r, label in pairs:
        cls = truth_class(label)
        if cls is None:
            continue
        for key in (
            (cls, condition(label)),
            (cls, "ALL"),
            ("ALL", condition(label)),
            ("ALL", "ALL"),
        ):
            groups.setdefault(key, []).append((r, label))
    return groups


def detection_rows(pairs: list[tuple[dict, dict]]) -> tuple[list[dict], list[dict]]:
    rows, confusion = [], []
    for cls in CLASSES:
        predicted = [(r, lb) for r, lb in pairs if r.get("document_class") == cls]
        actual = [(r, lb) for r, lb in pairs if truth_class(lb) == cls]
        tp = sum(correct(r, lb) for r, lb in predicted)
        rows.append(rate("precision", cls, "ALL", tp, len(predicted)))
        rows.append(rate("recall", cls, "ALL", tp, len(actual)))
        p = tp / len(predicted) if predicted else 0.0
        r_ = tp / len(actual) if actual else 0.0
        f1 = 2 * p * r_ / (p + r_) if p + r_ else 0.0
        scored = [(r.get("document_confidence") or 0.0, correct(r, lb)) for r, lb in predicted]
        rows.append({"metric": "f1", "class": cls, "condition": "ALL", "pct": round(100 * f1, 2)})
        ap = average_precision(scored, len(actual))
        rows.append({"metric": "ap50", "class": cls, "condition": "ALL", "pct": round(100 * ap, 2)})
    negatives = [(r, lb) for r, lb in pairs if truth_class(lb) is None]
    flagged = sum(r.get("document_class") is not None for r, _ in negatives)
    rows.append(rate("false_positive_rate", "negative", "ALL", flagged, len(negatives)))
    for truth in (*CLASSES, None):
        row: dict[str, Any] = {"truth": truth or "none"}
        for pred in (*CLASSES, None):
            row[f"pred_{pred or 'none'}"] = sum(
                truth_class(lb) == truth and r.get("document_class") == pred for r, lb in pairs
            )
        confusion.append(row)
    return rows, confusion


def corner_rows(pairs: list[tuple[dict, dict]]) -> list[dict]:
    from ml.eval import corner_error_pct, summarize_pct

    by_class: dict[str, list[float]] = {}
    for r, lb in pairs:
        if correct(r, lb):
            err = corner_error_pct(
                [tuple(p) for p in r["card_corners"]], [tuple(p) for p in lb["card_corners"]]
            )
            by_class.setdefault(truth_class(lb), []).append(err)
            by_class.setdefault("ALL", []).append(err)
    return [{"class": c, **summarize_pct(v)} for c, v in sorted(by_class.items())]


def field_rows(pairs: list[tuple[dict, dict]]) -> list[dict]:
    rows = []
    for (cls, cond), group in sorted(grouped(pairs).items()):
        fields = covered = boxes = useful = 0
        ious, over = [], []
        for r, lb in group:
            size = tuple(lb["image_size"])
            polys = [b["polygon"] for b in r.get("boxes", [])]
            sensitive = [
                f["polygon"] for f in lb["fields"] if f.get("sensitive") and f.get("polygon")
            ]
            for poly in sensitive:
                fields += 1
                covered += covered_share(poly, polys, size) >= FULLY_COVERED
            sens_mask = _mask(size, sensitive)
            for poly in polys:
                boxes += 1
                useful += ImageChops.multiply(_mask(size, [poly]), sens_mask).histogram()[255] > 0
            if polys and sensitive:
                box_mask = _mask(size, polys)
                inter = ImageChops.multiply(box_mask, sens_mask).histogram()[255]
                union = ImageChops.lighter(box_mask, sens_mask).histogram()[255]
                ious.append(inter / union if union else 0.0)
                o = over_redaction(lb, polys, size)
                if o is not None:
                    over.append(o)
        rows.append(rate("field_recall", cls, cond, covered, fields))
        rows.append(rate("box_precision", cls, cond, useful, boxes))
        rows.append(median_row("coverage_iou_median", cls, cond, ious))
        rows.append(median_row("over_coverage_median", cls, cond, over))
    return rows


def median_row(metric: str, cls: str, cond: str, values: list[float]) -> dict[str, Any]:
    median = round(100 * statistics.median(values), 2) if values else 0.0
    return {"metric": metric, "class": cls, "condition": cond, "of": len(values), "pct": median}


def pipeline_rows(pairs: list[tuple[dict, dict]]) -> list[dict]:
    rows = []
    for (cls, cond), group in sorted(grouped(pairs).items()):
        handled = [r for r, _ in group if r["state"] in ("REDACTED", "NEEDS_CHECK")]
        n = len(handled)
        rows.append(
            rate("inconclusive", cls, cond, sum(r["state"] == "NEEDS_CHECK" for r in handled), n)
        )
        rows.append(rate("retried", cls, cond, sum(r["retries"] >= 1 for r in handled), n))
        fallback = sum(
            any(b["field_type"] == "whole_document" for b in r["boxes"]) for r in handled
        )
        rows.append(rate("whole_image_fallback", cls, cond, fallback, n))
        rows.append(
            rate("recheck_passed", cls, cond, sum(bool(r["recheck_passed"]) for r in handled), n)
        )
    return rows


def leak_rows(
    pairs: list[tuple[dict, dict]], export_dir: Path, labels_dir: Path, reader: Any = None
) -> list[dict]:
    """D11-D13 on the exported copies, by the RQ4 rule (PaddleOCR judge)."""
    from PIL import Image

    from validation.leaktest.core import judge_image, load_boxes, match_labels

    if reader is None:
        from validation.leaktest.ocr import paddle_read as reader

    by_image: dict[str, list] = {}
    for image_id, image_path, label_path in match_labels(export_dir, labels_dir):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        boxes, source = load_boxes(image_path, label)
        with Image.open(image_path) as img:
            by_image[image_id] = judge_image(
                img.convert("RGB"), label, image_id, reader,
                boxes=boxes, box_source=source, source=image_path,
            )  # fmt: skip
    rows = []
    for (cls, cond), group in sorted(grouped(pairs).items()):
        fields = [f for r, _ in group for f in field_leaks(by_image.get(r["id"], []))]
        for kind in ("text", "picture"):
            sel = [f for f in fields if f["kind"] == kind]
            rows.append(rate(f"{kind}_leaked", cls, cond, sum(f["leaked"] for f in sel), len(sel)))
    return rows


def write_csv(rows: list[dict], path: Path) -> None:
    columns: list[str] = []
    for row in rows:
        columns += [k for k in row if k not in columns]
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def run_pipeline(
    export_dir: Path, labels_dir: Path, out: Path, leaks: bool = True, reader: Any = None
) -> dict[str, Path]:
    pairs = load(export_dir, labels_dir)
    if not pairs:
        raise ValueError(f"No test-split images in {export_dir}")
    out.mkdir(parents=True, exist_ok=True)
    detection, confusion = detection_rows(pairs)
    tables = {
        "detection": detection,
        "confusion": confusion,
        "corners": corner_rows(pairs),
        "fields": field_rows(pairs),
        "pipeline": pipeline_rows(pairs),
    }
    if leaks:
        tables["leaks"] = leak_rows(pairs, export_dir, labels_dir, reader)
    written = {}
    for name, rows in tables.items():
        written[name] = out / f"{name}.csv"
        write_csv(rows, written[name])
    return written
