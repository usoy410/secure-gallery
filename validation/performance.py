"""Summarize on-device batch timings for #32 without treating emulator runs as acceptance."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
from collections import Counter
from pathlib import Path

STAGES = ("decode", "detect", "ocr_localize", "recheck", "hash_redact_save", "total")
TARGETS_MS = {"non_document": 150, "flagged": 1500}
CLASSES = {"philsys", "lto", "payment_card"}


def measurement(value: object) -> float | None:
    """Invalid/missing measurements are not zero-duration observations."""
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return float(value) if math.isfinite(value) and value >= 0 else None


def distribution(values: list[float]) -> dict:
    if not values:
        return {"count": 0, "median": None, "p90": None, "max": None}
    ordered = sorted(values)
    rank = (len(ordered) - 1) * 0.9
    lower = math.floor(rank)
    upper = math.ceil(rank)
    p90 = ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)
    return {
        "count": len(values),
        "median": statistics.median(values),
        "p90": p90,
        "max": max(values),
    }


def cohort(image: dict) -> str | None:
    if image.get("state") == "NOT_DETECTED" and image.get("document_class") is None:
        return "non_document"
    if image.get("document_class") in CLASSES and image.get("state") in {"REDACTED", "NEEDS_CHECK"}:
        return "flagged"
    return None


def summarize(payload: dict, device_kind: str, ram_mb: float | None = None) -> dict:
    if device_kind not in {"phone", "emulator"}:
        raise ValueError("device_kind must be phone or emulator")
    if ram_mb is not None and (measurement(ram_mb) is None or ram_mb == 0):
        raise ValueError("RAM must be a finite positive number of MiB")
    images = payload.get("images")
    if not isinstance(images, list) or not images:
        raise ValueError("Expected nonempty images from a pipeline batch export")
    ids: set[str] = set()
    for image in images:
        if not isinstance(image, dict) or not isinstance(image.get("id"), str):
            raise ValueError("Every image needs a string id")
        if image["id"] in ids:
            raise ValueError("Duplicate image ids would bias timing statistics")
        ids.add(image["id"])
        if not isinstance(image.get("timings_ms", {}), dict):
            raise ValueError("timings_ms must be an object")
    excluded = sum(cohort(image) is None for image in images)
    rows, targets = [], []
    for name in ("all", "non_document", "flagged"):
        group = images if name == "all" else [i for i in images if cohort(i) == name]
        for stage in STAGES:
            values = [
                v
                for image in group
                if (v := measurement(image.get("timings_ms", {}).get(stage))) is not None
            ]
            stats = distribution(values)
            rows.append(
                {
                    "cohort": name,
                    "stage": stage,
                    "images": len(group),
                    "missing_or_invalid": len(group) - len(values),
                    **{f"{k}_ms" if k != "count" else k: v for k, v in stats.items()},
                }
            )
            if stage == "total" and name in TARGETS_MS:
                status = "not_measured"
                if stats["count"]:
                    if device_kind == "emulator":
                        status = "diagnostic_only"
                    elif len(values) != len(group) or excluded:
                        status = "incomplete"
                    else:
                        status = (
                            "within_target"
                            if stats["median"] <= TARGETS_MS[name]
                            else "over_target"
                        )
                targets.append(
                    {
                        "cohort": name,
                        "limit_ms": TARGETS_MS[name],
                        "status": status,
                        "count": stats["count"],
                        "images": len(group),
                        "median_ms": stats["median"],
                    }
                )
    memory = [v for image in images if (v := measurement(image.get("peak_memory_mb"))) is not None]
    return {
        "device": payload.get("device"),
        "device_kind": device_kind,
        "ram_mib": ram_mb,
        "images": len(images),
        "states": dict(Counter(i.get("state", "missing") for i in images)),
        "unclassified_or_unfinished": excluded,
        "percentile_method": "linear interpolation, rank=(n-1)*0.9",
        "stages": rows,
        "latency_targets": targets,
        "sampled_heap_peak_mb": {"count": len(memory), "max": max(memory) if memory else None},
        "limitations": [
            "Batch per-image timings are not the elapsed time of a 1000-photo first scan.",
            "Heap memory is sampled at stage boundaries; it is not OS peak RSS or device RAM.",
            "Stages may overlap; hash_redact_save is a residual, not isolated hashing time.",
            "Latency targets describe observed predictions, not ground-truth detection accuracy.",
            "Device kind and RAM are operator supplied; phone results require a physical device.",
        ],
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path, help="results.json from pipeline batch export")
    parser.add_argument("--device-kind", choices=("phone", "emulator"), required=True)
    parser.add_argument("--ram-mib", type=float)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    raw = args.results.read_bytes()
    report = summarize(json.loads(raw), args.device_kind, args.ram_mib)
    report["source_sha256"] = hashlib.sha256(raw).hexdigest()
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "summary.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    with (args.out / "stages.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(report["stages"][0]))
        writer.writeheader()
        writer.writerows(report["stages"])
    print(f"Performance summary and stage table written to {args.out}")


if __name__ == "__main__":
    main()
