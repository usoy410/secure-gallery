"""CLI: python -m validation.report rq4 [--config ...] [--data ...] [--out ...] [--limit N]

       python -m validation.report rq4-examples [--data ...] [--out ...]
"""

from __future__ import annotations

import argparse
from pathlib import Path

from validation.report.examples import write_examples
from validation.report.rq4 import DEFAULT_CONFIG, DEFAULT_DATA, DEFAULT_OUT, PAD_GRID, run_rq4


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="python -m validation.report")
    sub = parser.add_subparsers(dest="report", required=True)
    rq4 = sub.add_parser("rq4", help="compare localization strategies by image condition")
    rq4.add_argument("--config", type=Path, default=DEFAULT_CONFIG,
                     help="dataset config, used to build the benchmark if --data is empty")
    rq4.add_argument("--data", type=Path, default=DEFAULT_DATA, help="benchmark folder")
    rq4.add_argument("--out", type=Path, default=DEFAULT_OUT, help="report folder")
    rq4.add_argument("--limit", type=int, default=None,
                     help="score at most N images per split (quick check, not for results)")
    rq4.add_argument("--pads", default=",".join(map(str, PAD_GRID)),
                     help="OCR box paddings to try on val, comma-separated")  # fmt: skip
    examples = sub.add_parser("rq4-examples",
                              help="rebuild the RQ4 example gallery from an earlier run (no OCR)")
    examples.add_argument("--data", type=Path, default=DEFAULT_DATA, help="benchmark folder")
    examples.add_argument("--out", type=Path, default=DEFAULT_OUT, help="report folder")
    args = parser.parse_args(argv)

    if args.report == "rq4-examples":
        written = write_examples(args.data, args.out)
        print(f"Wrote {len(written)} examples to {args.out}/examples")
        return

    pads = [int(p) for p in args.pads.split(",") if p.strip()]
    result = run_rq4(args.data, args.out, args.config, limit=args.limit, pad_grid=pads)
    print(f"Tuned OCR pad: {result['pad']} px. Wrote {args.out}/rq4_results.csv, "
          f"{len(result['charts'])} charts and README.md")  # fmt: skip


if __name__ == "__main__":
    main()
