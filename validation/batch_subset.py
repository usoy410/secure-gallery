"""Pick the images for an on-device batch export (#29) from the generated benchmark.

Copies an evenly spread, deterministic subset of one split to ``<out>/images`` (what
``adb push`` sends to the phone) and their labels to ``<out>/labels`` (kept on the desktop
for ``validation.report``, never pushed). Failed-redaction cases are left out: they are
already redacted and test the leak test, not the pipeline.

Usage: python -m validation.batch_subset --count 50 --out data/batch_input
"""

from __future__ import annotations

import argparse
import csv
import shutil
from pathlib import Path


def pick(manifest: Path, split: str, count: int) -> list[dict[str, str]]:
    """``count`` rows of ``split``, spread evenly over the manifest order (all if fewer)."""
    with manifest.open(encoding="utf-8") as fh:
        rows = [
            r
            for r in csv.DictReader(fh)
            if r["split"] == split and r["is_failed_redaction"] != "True"
        ]
    if count >= len(rows):
        return rows
    step = len(rows) / count
    return [rows[int(i * step)] for i in range(count)]


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data", type=Path, default=Path("data/benchmark"))
    parser.add_argument("--split", default="test")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--out", type=Path, default=Path("data/batch_input"))
    args = parser.parse_args(argv)
    rows = pick(args.data / "manifest.csv", args.split, args.count)
    for sub in ("images", "labels"):
        shutil.rmtree(args.out / sub, ignore_errors=True)
        (args.out / sub).mkdir(parents=True)
    for r in rows:
        shutil.copy(args.data / r["image_path"], args.out / "images")
        shutil.copy(args.data / r["label_path"], args.out / "labels")
    print(f"{len(rows)} {args.split} images in {args.out / 'images'}, labels beside them")


if __name__ == "__main__":
    main()
