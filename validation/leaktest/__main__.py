"""CLI: python -m validation.leaktest <redacted_dir> --labels <dir> [--out <dir>]

Writes <out>/leaks.csv (one row per image x sensitive field x channel),
<out>/leaks_wide.csv (one row per image x field, one column per channel) and
<out>/summary.csv (counts and percentages per class, condition and channel), and
prints the summary table. With --fail-on-leak it exits with status 1 if anything
leaked. Redaction boxes are read from <id>.boxes.json beside each
image or from the label; without them the field polygon stands in for the box. The
file itself is also checked for an EXIF thumbnail and identifying metadata.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image

from validation.leaktest.core import (
    Reader,
    format_summary,
    has_leak,
    judge_image,
    load_boxes,
    match_labels,
    summarize,
    write_leaks,
    write_leaks_wide,
    write_summary,
)


def run(redacted_dir: Path, labels_dir: Path, out: Path, reader: Reader | None = None) -> list:
    import json

    if reader is None:
        from validation.leaktest.ocr import paddle_read

        reader = paddle_read
    results = []
    for image_id, image_path, label_path in match_labels(redacted_dir, labels_dir):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        boxes, source = load_boxes(image_path, label)
        with Image.open(image_path) as img:
            results += judge_image(
                img.convert("RGB"),
                label,
                image_id,
                reader,
                boxes=boxes,
                box_source=source,
                source=image_path,
            )
    write_leaks(results, out / "leaks.csv")
    write_leaks_wide(results, out / "leaks_wide.csv")
    summary = summarize(results)
    write_summary(summary, out / "summary.csv")
    return summary


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="python -m validation.leaktest",
        description="Judge redacted images for visible-pixel and opacity leaks.",
    )
    parser.add_argument("redacted_dir", type=Path, help="folder of redacted images")
    parser.add_argument("--labels", type=Path, required=True, help="folder of ground-truth labels")
    parser.add_argument("--out", type=Path, default=Path("data/leaktest"))
    parser.add_argument(
        "--fail-on-leak",
        action="store_true",
        help="exit with status 1 if any field or file leaked (for CI)",
    )
    args = parser.parse_args(argv)
    for folder in (args.redacted_dir, args.labels):
        if not folder.is_dir():
            parser.error(f"folder not found: {folder}")
    try:
        summary = run(args.redacted_dir, args.labels, args.out)
    except (FileNotFoundError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
    print(format_summary(summary))
    print(f"\nWrote {args.out / 'leaks.csv'} and {args.out / 'summary.csv'}")
    if args.fail_on_leak and has_leak(summary):
        print("Leak found: see leaks_wide.csv", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
