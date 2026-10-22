"""RQ4: how the three localization strategies compare as image conditions get worse.

For every card image in the benchmark's val and test splits, each strategy from
validation.localize redacts the image (solid fill, JPEG q95, as the app saves it), and
the leak test from validation.leaktest judges the saved file. Results are grouped by
strategy x condition x severity.

What counts as a leak: a text field leaks when an attacker can recover its text through
any recovery channel (visible pixels, partial characters, see-through fill, EXIF
thumbnail, card-number structure). A picture field (face, QR code, signature) has no
text to recover, so the opacity channel, an uncovered or see-through region, decides it.
The stricter reading, where any channel including whole-field opacity counts for text
fields too, is reported alongside as strict_leak_rate. Mosaic/blur is a risk flag only.

Thresholds: the only tunable one is the padding OCR-first adds around each word box.
It is picked on the val split (lowest leak rate, then least over-redaction, then the
smaller pad) and the test split is scored once with that value.
"""

from __future__ import annotations

import csv
import json
from collections.abc import Callable, Iterable
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from PIL import Image, ImageChops, ImageDraw

from validation.leaktest.core import PICTURE_FIELDS, RISK_CHANNELS, judge_image
from validation.localize.core import (
    OCR_PAD,
    STRATEGIES,
    load_rules,
    load_templates,
    localize,
    redact,
    save_jpeg,
)
from validation.report.stats import wilson_interval

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "configs" / "dataset.yaml"
DEFAULT_DATA = REPO_ROOT / "data" / "benchmark"
DEFAULT_OUT = REPO_ROOT / "reports" / "rq4"
PAD_GRID = (2, 4, 8, 12)
FILE_CHANNELS = frozenset({"metadata"})  # judged per file, not per field
# Text fields are judged on whether their text can be recovered; whole-field opacity
# only says the cover is not one uniform fill over the field, which tight boxes over
# the text alone always fail.
TEXT_EXCLUDED = RISK_CHANNELS | {"opacity"}

Reader = Callable[[Image.Image], str]


@dataclass
class ImageOutcome:
    split: str
    strategy: str
    pad: int
    image_id: str
    document_class: str
    condition: str
    severity: int
    fields: int
    leaked_fields: int
    strict_leaked_fields: int
    fallback: bool
    over_redacted: float | None  # share of the non-sensitive card area covered
    field_rows: list[dict[str, Any]] = field(default_factory=list, repr=False)


# --- data ---------------------------------------------------------------------------


def ensure_dataset(data_dir: Path, config: Path) -> Path:
    """The benchmark under ``data_dir``; built from ``config`` when it does not exist yet."""
    if not (data_dir / "manifest.csv").exists():
        from ml.dataset import build_full_dataset

        build_full_dataset(config, out_dir_override=data_dir)
    return data_dir


def card_records(data_dir: Path, splits: Iterable[str], limit: int | None = None) -> list[dict]:
    """Card images (no negatives, no failed-redaction cases) of the given splits."""
    wanted = set(splits)
    with (data_dir / "manifest.csv").open(encoding="utf-8") as fh:
        rows = [
            r for r in csv.DictReader(fh)
            if r["split"] in wanted
            and r["is_negative"] == "False"
            and r["is_failed_redaction"] == "False"
        ]  # fmt: skip
    rows.sort(key=lambda r: (r["split"], r["base_image_id"], r["condition"], int(r["severity"])))
    if limit is None:
        return rows
    kept: list[dict] = []
    for split in sorted(wanted):
        kept += [r for r in rows if r["split"] == split][:limit]
    return kept


# --- measures ---------------------------------------------------------------------------


def _mask(size: tuple[int, int], polygons: Iterable) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    for poly in polygons:
        draw.polygon([tuple(p) for p in poly], fill=255)
    return mask


def over_redaction(label: dict, boxes: Iterable, size: tuple[int, int]) -> float | None:
    """Share of the card's non-sensitive area (card minus sensitive fields) under a box."""
    corners = label.get("card_corners") or []
    if len(corners) < 3:
        return None
    card = _mask(size, [corners])
    sensitive = _mask(size, [f["polygon"] for f in label.get("fields", []) if f.get("sensitive")])
    free = ImageChops.subtract(card, sensitive)
    free_area = free.histogram()[255]
    if free_area == 0:
        return None
    covered = ImageChops.multiply(free, _mask(size, boxes)).histogram()[255]
    return covered / free_area


def field_leaks(results: list) -> list[dict[str, Any]]:
    """One row per sensitive field: ``leaked`` by the RQ4 rule, ``strict`` by any channel."""
    by_field: dict[tuple[str, str], list] = {}
    for r in results:
        if r.channel in FILE_CHANNELS:
            continue
        by_field.setdefault((r.field_type, r.field_name), []).append(r)
    rows = []
    for (field_type, name), group in by_field.items():
        hits = sorted(
            r.channel for r in group if r.judged and r.leaked and r.channel not in RISK_CHANNELS
        )
        excluded = RISK_CHANNELS if field_type in PICTURE_FIELDS else TEXT_EXCLUDED
        leaked = any(c not in excluded for c in hits)
        rows.append({"field_type": field_type, "field_name": name, "leaked": leaked,
                     "strict": bool(hits), "channels": ";".join(hits)})  # fmt: skip
    return rows


# --- one image -----------------------------------------------------------------------------


def evaluate(
    record: dict,
    data_dir: Path,
    strategy: str,
    pad: int,
    work_dir: Path,
    reader: Reader,
    context: dict[str, Any],
) -> ImageOutcome:
    label = json.loads((data_dir / record["label_path"]).read_text(encoding="utf-8"))
    with Image.open(data_dir / record["image_path"]) as img:
        image = img.convert("RGB")
    result = localize(
        image,
        strategy,
        is_document=True,
        template=context["templates"].get(label.get("variant", "")),
        raw_corners=label.get("card_corners"),
        rules=context["rules"],
        excluded=context["excluded"],
        ocr_pad=pad,
    )
    image_id = Path(record["image_path"]).stem
    saved = work_dir / record["split"] / strategy / f"pad{pad}" / f"{image_id}.jpg"
    save_jpeg(redact(image, result.boxes), saved)
    boxes = [list(b.polygon) for b in result.boxes]
    with Image.open(saved) as img:
        judged = judge_image(
            img.convert("RGB"), label, image_id, reader, boxes=boxes, box_source="file",
            source=saved,
        )  # fmt: skip
    rows = field_leaks(judged)
    return ImageOutcome(
        split=record["split"],
        strategy=strategy,
        pad=pad,
        image_id=image_id,
        document_class=record["document_class"],
        condition=record["condition"],
        severity=int(record["severity"]),
        fields=len(rows),
        leaked_fields=sum(r["leaked"] for r in rows),
        strict_leaked_fields=sum(r["strict"] for r in rows),
        fallback=result.fallback,
        over_redacted=over_redaction(label, boxes, image.size),
        field_rows=rows,
    )


# --- aggregation -------------------------------------------------------------------------------


def aggregate(outcomes: list[ImageOutcome]) -> list[dict[str, Any]]:
    """Per strategy x condition x severity, plus an ALL row per strategy."""
    groups: dict[tuple[str, str, str], list[ImageOutcome]] = {}
    for o in outcomes:
        for key in ((o.strategy, o.condition, str(o.severity)), (o.strategy, "ALL", "ALL")):
            groups.setdefault(key, []).append(o)

    def order(key: tuple[str, str, str]) -> tuple:
        strategy, condition, severity = key
        return (STRATEGIES.index(strategy) if strategy in STRATEGIES else 99,
                condition == "ALL", condition != "none", condition,
                -1 if severity == "ALL" else int(severity))  # fmt: skip

    rows = []
    for key in sorted(groups, key=order):
        group = groups[key]
        fields = sum(o.fields for o in group)
        leaked = sum(o.leaked_fields for o in group)
        strict = sum(o.strict_leaked_fields for o in group)
        low, high = wilson_interval(leaked, fields)
        images = len(group)
        leaky_images = sum(o.leaked_fields > 0 for o in group)
        fallbacks = sum(o.fallback for o in group)
        over = [o.over_redacted for o in group if o.over_redacted is not None]
        rows.append({
            "strategy": key[0],
            "condition": key[1],
            "severity": key[2],
            "images": images,
            "fields": fields,
            "leaked_fields": leaked,
            "leak_rate": round(leaked / fields, 4) if fields else 0.0,
            "leak_ci_low": round(low, 4),
            "leak_ci_high": round(high, 4),
            "strict_leak_rate": round(strict / fields, 4) if fields else 0.0,
            "images_with_leak": leaky_images,
            "image_leak_rate": round(leaky_images / images, 4) if images else 0.0,
            "over_redacted_pct": round(100 * sum(over) / len(over), 2) if over else "",
            "fallbacks": fallbacks,
            "fallback_rate": round(fallbacks / images, 4) if images else 0.0,
        })  # fmt: skip
    return rows


def choose_pad(tuning: list[dict[str, Any]]) -> int:
    """Lowest leak rate, then least over-redaction, then the smaller pad."""
    def key(row: dict[str, Any]) -> tuple:
        over = row["over_redacted_pct"]
        return (row["leak_rate"], over if over != "" else 100.0, row["pad"])

    return min(tuning, key=key)["pad"] if tuning else OCR_PAD


# --- output -------------------------------------------------------------------------------------


def write_csv(rows: list[dict[str, Any]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        if not rows:
            fh.write("")
            return
        writer = csv.DictWriter(fh, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def write_charts(rows: list[dict[str, Any]], out: Path) -> list[Path]:
    """One PNG per condition: leak rate (with 95% CI) against severity, a line per strategy.
    Severity 0 is the same card without the condition."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    by_key = {(r["strategy"], r["condition"], r["severity"]): r for r in rows}
    conditions = sorted({r["condition"] for r in rows} - {"none", "ALL"})
    strategies = [s for s in STRATEGIES if any(r["strategy"] == s for r in rows)]
    written = []
    for condition in conditions:
        fig, ax = plt.subplots(figsize=(6, 4), dpi=100)
        for offset, strategy in zip((-0.06, 0.0, 0.06), strategies, strict=False):
            points = [(0, by_key.get((strategy, "none", "0")))]
            points += [(s, by_key.get((strategy, condition, str(s)))) for s in (1, 2, 3)]
            points = [(s, r) for s, r in points if r]
            if not points:
                continue
            xs = [s + offset for s, _ in points]
            ys = [100 * r["leak_rate"] for _, r in points]
            err = [[100 * (r["leak_rate"] - r["leak_ci_low"]) for _, r in points],
                   [100 * (r["leak_ci_high"] - r["leak_rate"]) for _, r in points]]  # fmt: skip
            ax.errorbar(xs, ys, yerr=err, marker="o", capsize=3, label=strategy)
        ax.set_title(f"Leak rate vs {condition.replace('_', ' ')} severity")
        ax.set_xlabel("Severity (0 = no condition)")
        ax.set_ylabel("Fields leaked (%)")
        ax.set_xticks([0, 1, 2, 3])
        ax.set_ylim(-2, 102)
        ax.grid(alpha=0.3)
        ax.legend()
        fig.tight_layout()
        path = out / f"leak_rate_{condition}.png"
        fig.savefig(path, metadata={"Software": None})
        plt.close(fig)
        written.append(path)
    return written


def _table(rows: list[dict[str, Any]], columns: list[str]) -> str:
    lines = ["| " + " | ".join(columns) + " |", "|" + "---|" * len(columns)]
    lines += ["| " + " | ".join(str(r[c]) for c in columns) + " |" for r in rows]
    return "\n".join(lines)


def write_readme(out: Path, summary: dict[str, Any], rows: list[dict[str, Any]],
                 tuning: list[dict[str, Any]]) -> None:  # fmt: skip
    overall = [r for r in rows if r["condition"] == "ALL"]
    headline = [
        {**r, "leak": f"{100 * r['leak_rate']:.1f}% ({r['leaked_fields']}/{r['fields']})",
         "95% CI": f"{100 * r['leak_ci_low']:.1f}–{100 * r['leak_ci_high']:.1f}%",
         "over-redacted": f"{r['over_redacted_pct']}%",
         "fallback": f"{100 * r['fallback_rate']:.1f}%"}
        for r in overall
    ]  # fmt: skip
    text = f"""# RQ4: localization strategies by image condition

Generated by `python -m validation.report rq4`. Re-run that command to reproduce every
file here; it rebuilds the benchmark from `{summary['config']}` if it is missing.

**Data:** {summary['test_images']} test images ({summary['test_fields']} sensitive
fields) from the synthetic benchmark, card images only. Negatives and the D7
failed-redaction cases are left out. Thresholds were tuned on {summary['val_images']}
validation images; the test split was scored once.

**Tuned threshold:** OCR box padding = **{summary['pad']} px** (in straightened card
space), chosen on val from {list(summary['pad_grid'])}. It applies to `ocr_first` and
`hybrid`; `layout_only` has no tunable threshold.

**Fixed values (not tuned):** leak = character error rate ≤ 0.3 or 4 consecutive digits
of an ID or card number recovered (§9.2); opacity fails below 99.5% uniform pixels;
partial-character band = 15% of the box; card-number leak at ≤ 1,000 Luhn-valid
candidates. Mosaic/blur is a risk flag and does not count as a leak here.

## Headline (test split, all conditions)

{_table(headline, ["strategy", "images", "leak", "95% CI", "over-redacted", "fallback"])}

## Files

- `rq4_results.csv`: the main table. One row per strategy × condition × severity, plus
  an `ALL` row per strategy. Severity 0 with condition `none` is the clean card.
  - `leak_rate`: share of sensitive fields an attacker could recover.
    `leaked_fields / fields`. A text field counts when its text comes back through any
    recovery channel: visible pixels, clipped characters at a box edge, a see-through
    fill, the EXIF thumbnail, or the card-number structure. A face, QR code or
    signature counts when its area is not fully and solidly covered (opacity).
  - `leak_ci_low`, `leak_ci_high`: 95% Wilson confidence interval for that rate.
    Small cells give wide intervals; compare strategies by whether intervals overlap.
  - `strict_leak_rate`: the same, but whole-field opacity also counts for text fields:
    any part of the field area left uncovered is a leak even when no text can be read.
    Tight boxes around the text (as `ocr_first` draws) always fail this reading.
  - `image_leak_rate`: share of images with at least one leaked field.
  - `over_redacted_pct`: average share of the card's non-sensitive area (headings,
    logos, sex, height, weight, background) that the strategy blacked out anyway.
  - `fallback_rate`: share of images where the strategy gave up and blacked out the
    whole image (no usable card corners and no OCR match).
- `rq4_fields.csv`: one row per strategy × test image × sensitive field, with
  `leaked` and the channels that recovered it. Use it to see which fields leak.
- `tuning.csv`: validation results for each padding tried for `ocr_first`.
- `leak_rate_<condition>.png`: leak rate (with 95% CI bars) against severity 1–3 for
  that condition, one line per strategy. Severity 0 is the clean card.

## Reading the results

- Lower `leak_rate` is better; so is lower `over_redacted_pct`. A strategy that leaks
  less but blacks out much more of the card trades usefulness for safety.
- `ocr_first` cannot see faces, QR codes or signatures, so its leak rate includes
  those picture fields; `layout_only` and `hybrid` map them from the template.
- These are synthetic cards. Treat the numbers as a controlled comparison between
  strategies, not as the leak rate on real photos.

## Validation tuning

{_table(tuning, ["pad", "images", "fields", "leaked_fields", "leak_rate",
                  "over_redacted_pct"])}
"""
    (out / "README.md").write_text(text, encoding="utf-8")


# --- pipeline ------------------------------------------------------------------------------------


def run_rq4(
    data_dir: Path = DEFAULT_DATA,
    out: Path = DEFAULT_OUT,
    config: Path = DEFAULT_CONFIG,
    reader: Reader | None = None,
    limit: int | None = None,
    pad_grid: Iterable[int] = PAD_GRID,
    progress: Callable[[str], None] = print,
) -> dict[str, Any]:
    if reader is None:
        from validation.leaktest.ocr import paddle_read

        reader = paddle_read
    data_dir = ensure_dataset(data_dir, config)
    work = data_dir / "rq4_work"
    context = {"templates": load_templates()}
    context["rules"], context["excluded"] = load_rules()
    pad_grid = tuple(pad_grid)

    val = card_records(data_dir, ["val"], limit)
    test = card_records(data_dir, ["test"], limit)

    tuning = []
    for pad in pad_grid:
        progress(f"tuning ocr_first pad={pad} on {len(val)} val images")
        outcomes = [evaluate(r, data_dir, "ocr_first", pad, work, reader, context) for r in val]
        overall = aggregate(outcomes)[-1:] if outcomes else []
        keep = ("images", "fields", "leaked_fields", "leak_rate", "over_redacted_pct")
        tuning += [{"pad": pad, **{k: row[k] for k in keep}} for row in overall]
    pad = choose_pad(tuning)

    outcomes = []
    for strategy in STRATEGIES:
        progress(f"scoring {strategy} on {len(test)} test images")
        outcomes += [evaluate(r, data_dir, strategy, pad, work, reader, context) for r in test]

    rows = aggregate(outcomes)
    out.mkdir(parents=True, exist_ok=True)
    write_csv(rows, out / "rq4_results.csv")
    write_csv(
        [{"strategy": o.strategy, "image_id": o.image_id, "document_class": o.document_class,
          "condition": o.condition, "severity": o.severity, **f}
         for o in outcomes for f in o.field_rows],
        out / "rq4_fields.csv",
    )  # fmt: skip
    write_csv(tuning, out / "tuning.csv")
    charts = write_charts(rows, out)
    summary = {
        "config": str(config.relative_to(REPO_ROOT)) if config.is_relative_to(REPO_ROOT)
        else str(config),
        "pad": pad,
        "pad_grid": pad_grid,
        "val_images": len(val),
        "test_images": len(test),
        "test_fields": sum(o.fields for o in outcomes if o.strategy == STRATEGIES[0]),
        "charts": [p.name for p in charts],
    }  # fmt: skip
    write_readme(out, summary, rows, tuning)
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return {**summary, "rows": rows, "outcomes": [asdict(o) for o in outcomes]}
