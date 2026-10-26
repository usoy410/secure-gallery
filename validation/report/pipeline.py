"""Pipeline metrics from the app's on-device batch export (#30; RQ2, RQ3, RQ5, RQ6 in §6, §9.1).

Reads ``results.json`` and the redacted copies written by ``scripts/batch_export.sh`` (#29),
pairs every image with its ground-truth label, and writes one table per measure:

- ``detection.csv``: per class and condition precision, recall, F1 and AP50, plus mAP50 and the
  false-positive rate on non-ID images. A detection is correct when the class matches and the
  detected card overlaps the true card with IoU >= 0.5. ``confusion.csv`` holds the class
  confusion matrix.
- ``corners.csv``: corner error of correct detections, as a percent of the card width.
- ``fields.csv``: matched-field detection precision, recall, and F1 (maximum-cardinality one-to-one
  field-type and IoU >= 0.5 matching), redaction coverage recall (a sensitive field fully covered,
  >= 99.5% of its area across all applied boxes including whole-document fallback), box precision
  (a box that touches a sensitive field), coverage IoU and over-coverage.
- ``pipeline.csv``: inconclusive (NEEDS_CHECK), retry, whole-image fallback and recheck-pass
  rates.
- ``recheck.csv``: sensitivity, false-negative, false-positive and inconclusive rates of the
  on-device recheck directly evaluating D7 benchmark redactions. Ordinary benchmark images
  are strictly excluded from the D7 cohort. Skipped/unrun cases are excluded from decision-quality
  denominators and reported separately alongside export coverage.
- ``leaks.csv``: the D11-D13 leak test on the exported copies, by the RQ4 rule.

Statistical treatment:
Wilson score confidence intervals apply strictly to binomial proportions (successes out of
independent Bernoulli trials: precision, recall, false-positive rate, inconclusive rate,
coverage recall, box precision). Aggregate summary metrics (mAP50, AP50, F1, field_f1) and
continuous distribution medians (coverage_iou_median, over_coverage_median) are non-binomial
macro-averages, harmonic means, or continuous distributions where binomial assumptions do
not hold; their Wilson intervals are undefined and omitted, while reporting explicit support
components (evaluated class count for mAP50, TP/FP/FN counts for F1 and AP) where applicable.
Only test-split images are scored.
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
D7_REDACTION_TYPES = ("complete", "partial", "misplaced", "ineffective")
FULLY_COVERED = 0.995  # the §8.5 / D11 99.5% rule
MATCH_IOU = 0.5


def load(export_dir: Path, labels_dir: Path, split: str = "test") -> list[tuple[dict, dict]]:
    """(result, label) pairs for every exported image of ``split``."""
    results: list[dict] = []
    seen_ids: set[str] = set()

    results_file = export_dir / "results.json"
    if results_file.exists():
        for r in json.loads(results_file.read_text(encoding="utf-8")).get("images", []):
            if r["id"] not in seen_ids:
                results.append(r)
                seen_ids.add(r["id"])

    # Load verification-only D7 export results if present
    d7_file = export_dir / "d7_results.json"
    if d7_file.exists():
        for r in json.loads(d7_file.read_text(encoding="utf-8")).get("images", []):
            if r["id"] not in seen_ids:
                results.append(r)
                seen_ids.add(r["id"])

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

    # All specific conditions present across samples, plus "ALL" overall
    conditions_present = sorted({condition(lb) for _, lb in pairs})
    cond_keys = ["ALL", *[c for c in conditions_present if c != "ALL"]]

    for cond_val in cond_keys:
        sub_pairs = (
            pairs if cond_val == "ALL" else [p for p in pairs if condition(p[1]) == cond_val]
        )
        ap_by_class: list[float] = []

        for cls in CLASSES:
            predicted = [(r, lb) for r, lb in sub_pairs if r.get("document_class") == cls]
            actual = [(r, lb) for r, lb in sub_pairs if truth_class(lb) == cls]

            # In condition breakdown, skip class if no actual or predicted items exist
            if cond_val != "ALL" and not predicted and not actual:
                continue

            tp = sum(correct(r, lb) for r, lb in predicted)
            fp = len(predicted) - tp
            fn = len(actual) - tp
            rows.append(rate("precision", cls, cond_val, tp, len(predicted)))
            rows.append(rate("recall", cls, cond_val, tp, len(actual)))
            p = tp / len(predicted) if predicted else 0.0
            r_ = tp / len(actual) if actual else 0.0
            f1 = 2 * p * r_ / (p + r_) if p + r_ else 0.0
            scored = [
                (r.get("document_confidence") or 0.0, correct(r, lb)) for r, lb in predicted
            ]
            rows.append(
                {
                    "metric": "f1",
                    "class": cls,
                    "condition": cond_val,
                    "count": tp,
                    "of": len(actual),
                    "tp": tp,
                    "fp": fp,
                    "fn": fn,
                    "pct": round(100 * f1, 2),
                }
            )
            ap = average_precision(scored, len(actual))
            rows.append(
                {
                    "metric": "ap50",
                    "class": cls,
                    "condition": cond_val,
                    "count": tp,
                    "of": len(actual),
                    "tp": tp,
                    "fp": fp,
                    "fn": fn,
                    "pct": round(100 * ap, 2),
                }
            )
            if actual:
                ap_by_class.append(ap)

        if ap_by_class or cond_val == "ALL":
            eval_count = len(ap_by_class)
            rows.append(
                {
                    "metric": "map50",
                    "class": "ALL",
                    "condition": cond_val,
                    "count": eval_count,
                    "of": eval_count,
                    "pct": round(100 * statistics.mean(ap_by_class), 2)
                    if ap_by_class
                    else 0.0,
                }
            )

        negatives = [(r, lb) for r, lb in sub_pairs if truth_class(lb) is None]
        if negatives or cond_val == "ALL":
            flagged = sum(r.get("document_class") is not None for r, _ in negatives)
            rows.append(rate("false_positive_rate", "negative", cond_val, flagged, len(negatives)))

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


def max_bipartite_matching(adj: list[list[int]], n_right: int) -> int:
    """Maximum-cardinality bipartite matching via augmenting paths (Kuhn's algorithm)."""
    match_right = [-1] * n_right

    def dfs(u: int, visited: list[bool]) -> bool:
        for v in adj[u]:
            if not visited[v]:
                visited[v] = True
                if match_right[v] < 0 or dfs(match_right[v], visited):
                    match_right[v] = u
                    return True
        return False

    matches = 0
    for u in range(len(adj)):
        visited = [False] * n_right
        if dfs(u, visited):
            matches += 1
    return matches


def match_fields(
    predicted: list[dict],
    sensitive: list[dict],
    size: tuple[int, int],
    min_iou: float = MATCH_IOU,
) -> tuple[int, int]:
    """Maximum-cardinality one-to-one matching between predicted boxes and sensitive fields.

    Requires BOTH:
    1. Matching field type (e.g. 'pcn' == 'pcn').
    2. Overlap geometry (polygon IoU >= min_iou).

    Returns (matched_predicted_count, matched_sensitive_count).
    Because it is a one-to-one matching, both counts equal the maximum cardinality match size.
    """
    n_pred = len(predicted)
    n_sens = len(sensitive)
    if n_pred == 0 or n_sens == 0:
        return 0, 0

    adj: list[list[int]] = [[] for _ in range(n_pred)]
    for p_idx, p in enumerate(predicted):
        p_type = p.get("field_type") or p.get("type")
        p_poly = p.get("polygon")
        if not p_type or not p_poly:
            continue
        for s_idx, s in enumerate(sensitive):
            s_type = s.get("field_type") or s.get("type")
            s_poly = s.get("polygon")
            if not s_type or not s_poly:
                continue
            if p_type == s_type:
                iou = polygon_iou(p_poly, s_poly, size)
                if iou >= min_iou:
                    adj[p_idx].append(s_idx)

    matched_count = max_bipartite_matching(adj, n_sens)
    return matched_count, matched_count


def field_rows(pairs: list[tuple[dict, dict]]) -> list[dict]:
    rows = []
    for (cls, cond), group in sorted(grouped(pairs).items()):
        fields = covered = boxes = useful = 0
        predicted_fields = matched_fields = matched_predictions = 0
        ious, over = [], []
        for r, lb in group:
            size = tuple(lb["image_size"])
            all_boxes = [b for b in r.get("boxes", []) if b.get("polygon")]
            all_polys = [b["polygon"] for b in all_boxes]
            detection_boxes = [
                b for b in all_boxes if b.get("field_type") != "whole_document"
            ]

            sensitive_fields = [
                f for f in lb.get("fields", []) if f.get("sensitive") and f.get("polygon")
            ]
            sensitive_polys = [f["polygon"] for f in sensitive_fields]

            p_match, f_match = match_fields(detection_boxes, sensitive_fields, size)
            predicted_fields += len(detection_boxes)
            matched_predictions += p_match
            matched_fields += f_match

            for poly in sensitive_polys:
                fields += 1
                covered += covered_share(poly, all_polys, size) >= FULLY_COVERED

            sens_mask = _mask(size, sensitive_polys)
            for poly in all_polys:
                boxes += 1
                useful += ImageChops.multiply(_mask(size, [poly]), sens_mask).histogram()[255] > 0

            if all_polys and sensitive_polys:
                box_mask = _mask(size, all_polys)
                inter = ImageChops.multiply(box_mask, sens_mask).histogram()[255]
                union = ImageChops.lighter(box_mask, sens_mask).histogram()[255]
                ious.append(inter / union if union else 0.0)
                o = over_redaction(lb, all_polys, size)
                if o is not None:
                    over.append(o)

        rows.append(rate("field_precision", cls, cond, matched_predictions, predicted_fields))
        rows.append(rate("field_recall", cls, cond, matched_fields, fields))
        p = matched_predictions / predicted_fields if predicted_fields else 0.0
        r_ = matched_fields / fields if fields else 0.0
        f1 = 2 * p * r_ / (p + r_) if p + r_ else 0.0
        tp = matched_fields
        fp = predicted_fields - matched_predictions
        fn = fields - matched_fields
        rows.append(
            {
                "metric": "field_f1",
                "class": cls,
                "condition": cond,
                "count": tp,
                "of": fields,
                "tp": tp,
                "fp": fp,
                "fn": fn,
                "pct": round(100 * f1, 2),
            }
        )
        rows.append(rate("coverage_recall", cls, cond, covered, fields))
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
        handled = [r for r, _ in group if r.get("state") in ("REDACTED", "NEEDS_CHECK")]
        n = len(handled)
        rows.append(
            rate(
                "inconclusive",
                cls,
                cond,
                sum(r.get("state") == "NEEDS_CHECK" for r in handled),
                n,
            )
        )
        rows.append(rate("retried", cls, cond, sum(r.get("retries", 0) >= 1 for r in handled), n))
        fallback = sum(
            any(b.get("field_type") == "whole_document" for b in r.get("boxes", []))
            for r in handled
        )
        rows.append(rate("whole_image_fallback", cls, cond, fallback, n))
        rows.append(
            rate(
                "recheck_passed",
                cls,
                cond,
                sum(bool(r.get("recheck_passed")) for r in handled),
                n,
            )
        )
    return rows


def extract_recheck_decision(result: dict, label: dict | None = None) -> str:
    """Extract explicit on-device or verification recheck decision.

    Returns one of: 'clean', 'failed', 'inconclusive', 'skipped'.
    """
    outcome = (
        result.get("recheck_outcome")
        or result.get("recheck_decision")
        or result.get("verification_outcome")
    )
    if outcome is not None:
        outcome = str(outcome).strip().lower()
        if outcome in ("passed", "clean", "ok"):
            return "clean"
        if outcome in ("failed", "bad", "rejected"):
            return "failed"
        if outcome in ("inconclusive", "needs_check"):
            return "inconclusive"
        if outcome in ("skipped", "unrun", "none"):
            return "skipped"

    if "verification_passed" in result and result["verification_passed"] is not None:
        return "clean" if result["verification_passed"] else "failed"

    state = result.get("state")
    recheck_passed = result.get("recheck_passed")

    if recheck_passed is None or state in ("FAILED", "NOT_DETECTED", "PENDING"):
        return "skipped"
    if recheck_passed is True:
        return "clean"
    if recheck_passed is False:
        if state in ("REDACTED", "NEEDS_CHECK", "VERIFIED"):
            return "failed"
        return "skipped"
    if state == "NEEDS_CHECK":
        return "inconclusive"
    return "skipped"


def is_d7_cohort_item(label: dict) -> bool:
    """True only for explicit D7 benchmark cases (complete or failed redactions).

    Ordinary generated benchmark images (where is_failed_redaction is False and redaction_type
    is 'none' or absent) are strictly excluded.
    """
    rtype = label.get("redaction_type")
    if rtype in D7_REDACTION_TYPES:
        return True
    if label.get("is_failed_redaction") is True:
        return True
    return False


def measure_d7_recheck(
    image: Any,
    label: dict,
    reader: Any = None,
    min_solid_share: float = FULLY_COVERED,
    tolerance: int = 8,
) -> str:
    """Desktop simulation of redaction verification rules (§8.5, RQ6).

    Note: for §9.1 on-device recheck sensitivity, actual on-device D7 export outcomes
    from Android Recheck are scored; this desktop function must not be claimed as
    on-device sensitivity. Evaluates pixel opacity and sensitive text coverage.

    Returns:
        'clean' if recheck passes, 'failed' if failure is detected, or 'inconclusive'.
    """
    from PIL import Image

    if not isinstance(image, Image.Image):
        img = Image.open(image).convert("RGB")
    else:
        img = image.convert("RGB")

    redaction = label.get("redaction", {})
    boxes = redaction.get("redaction_boxes") or []
    sensitive = [
        f["polygon"]
        for f in label.get("fields", [])
        if f.get("sensitive") and f.get("polygon")
    ]
    if not boxes and sensitive:
        return "failed"

    w, h = img.size
    for poly in boxes:
        xs = [pt[0] for pt in poly]
        ys = [pt[1] for pt in poly]
        min_x, max_x = max(0, int(min(xs)) + 3), min(w, int(max(xs)) - 3)
        min_y, max_y = max(0, int(min(ys)) + 3), min(h, int(max(ys)) - 3)
        if min_x >= max_x or min_y >= max_y:
            continue
        crop = img.crop((min_x, min_y, max_x, max_y))
        getter = getattr(crop, "get_flattened_data", crop.getdata)
        pixels = list(getter())
        solid_count = sum(
            1 for p in pixels if p[0] <= tolerance and p[1] <= tolerance and p[2] <= tolerance
        )
        if not pixels or (solid_count / len(pixels)) < min_solid_share:
            return "failed"

    if reader is not None:
        card = label.get("card_corners")
        if card:
            xs = [pt[0] for pt in card]
            ys = [pt[1] for pt in card]
            crop_card = img.crop(
                (
                    max(0, int(min(xs))),
                    max(0, int(min(ys))),
                    min(w, int(max(xs))),
                    min(h, int(max(ys))),
                )
            )
            read_text = reader(crop_card)
        else:
            read_text = reader(img)
        if read_text and any(char.isalnum() for char in str(read_text)):
            return "failed"

    for s_poly in sensitive:
        if covered_share(s_poly, boxes, (w, h)) < FULLY_COVERED:
            return "failed"

    return "clean"


def recheck_rows(pairs: list[tuple[dict, dict]]) -> list[dict]:
    """D7 failed-redaction detection quality of the on-device recheck (§8.5, §9.1, RQ6).

    Evaluates explicit direct outcomes from the on-device recheck against ground truth
    labels. Ordinary benchmark images are excluded from the D7 cohort. Skipped/unrun
    cases are excluded from decision-quality denominators and reported separately.
    """
    rows = []
    d7 = [(r, lb) for r, lb in pairs if is_d7_cohort_item(lb)]
    if not d7:
        for metric in (
            "recheck_sensitivity",
            "recheck_false_negative_rate",
            "recheck_false_positive_rate",
            "recheck_inconclusive_rate",
            "recheck_skipped_rate",
            "recheck_export_coverage",
        ):
            rows.append(rate(metric, "ALL", "ALL", 0, 0))
        return rows

    for (cls, cond), group in sorted(grouped(d7).items()):
        total = len(group)
        positives = negatives = tp = fn = fp = tn = inconclusive = skipped = 0
        for result, label in group:
            actual_failed = bool(label.get("is_failed_redaction"))
            decision = extract_recheck_decision(result, label)

            if decision == "skipped":
                skipped += 1
                continue

            if decision == "inconclusive":
                inconclusive += 1
                continue

            if actual_failed:
                positives += 1
                if decision == "failed":
                    tp += 1
                elif decision == "clean":
                    fn += 1
            else:
                negatives += 1
                if decision == "failed":
                    fp += 1
                elif decision == "clean":
                    tn += 1

        evaluated = total - skipped
        rows.append(rate("recheck_sensitivity", cls, cond, tp, positives))
        rows.append(rate("recheck_false_negative_rate", cls, cond, fn, positives))
        rows.append(rate("recheck_false_positive_rate", cls, cond, fp, negatives))
        rows.append(rate("recheck_inconclusive_rate", cls, cond, inconclusive, evaluated))
        rows.append(rate("recheck_skipped_rate", cls, cond, skipped, total))
        rows.append(rate("recheck_export_coverage", cls, cond, evaluated, total))
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
    # D7 exports run only Recheck on already-redacted images, without the detector
    # or redactor. Counting them as ordinary pipeline cases invents missed
    # detections and uncovered fields, changing unrelated benchmark scores.
    pipeline_pairs = [(r, lb) for r, lb in pairs if not is_d7_cohort_item(lb)]
    out.mkdir(parents=True, exist_ok=True)
    detection, confusion = detection_rows(pipeline_pairs)
    tables = {
        "detection": detection,
        "confusion": confusion,
        "corners": corner_rows(pipeline_pairs),
        "fields": field_rows(pipeline_pairs),
        "pipeline": pipeline_rows(pipeline_pairs),
        "recheck": recheck_rows(pairs),
    }
    if leaks:
        tables["leaks"] = leak_rows(pipeline_pairs, export_dir, labels_dir, reader)
    written = {}
    for name, rows in tables.items():
        written[name] = out / f"{name}.csv"
        write_csv(rows, written[name])
    return written
