"""CLI: python -m ml.generate --variant <id|all> --count N --seed S --out <dir>"""

from __future__ import annotations

import argparse
from pathlib import Path

from ml.generate.sample import write_dataset


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="python -m ml.generate",
        description="Render synthetic SPECIMEN cards with field-level JSON labels.",
    )
    parser.add_argument("--variant", required=True, help="template id, or 'all'")
    parser.add_argument("--count", type=int, default=10, help="images per variant")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--out", type=Path, default=Path("data/synthetic"))
    args = parser.parse_args(argv)
    if args.count < 1:
        parser.error("--count must be at least 1")
    written = write_dataset(args.variant, args.count, args.seed, args.out)
    print(f"Wrote {len(written)} images with labels under {args.out}")


if __name__ == "__main__":
    main()
