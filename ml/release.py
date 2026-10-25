"""Public release bundle of the synthetic benchmark (contribution 3, §5.3, #34).

`python -m ml.generate release --seed <S>` builds `data/release-<S>/` with:

- `images/`, `labels/`, `manifest.csv`: the dataset, from `configs/release.yaml`
- `templates/`: the card templates the dataset was rendered from
- `DATASHEET.md`: purpose, composition, counts, uses, license and known gaps
- `LICENSE-DATA.md`: CC BY 4.0 for the data, and the licenses of every third-party input
- `release.json`: seed, config, generator commit and counts
- `SHA256SUMS`: one line per file, sorted by path

`python -m ml.generate release --verify <bundle>` rebuilds the bundle from its own seed and
config in a temporary folder and compares every checksum. The build needs the five curated
photos (`python -m ml.fetch_photos`); they are hash-pinned, so the output is the same on
every machine.

Every image with a card must carry a visible SPECIMEN watermark: the renderer measures it
(`watermark_contrast` in the label) and the build fails below `MIN_WATERMARK_CONTRAST`.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

import yaml

from ml.dataset import build_full_dataset
from ml.fetch_photos import PUBLIC_DOMAIN_PHOTOS
from ml.negatives import get_valid_curated_photos
from ml.renderer import MIN_WATERMARK_CONTRAST

REPO_ROOT = Path(__file__).resolve().parents[1]
RELEASE_CONFIG = REPO_ROOT / "configs" / "release.yaml"
DATA_LICENSE = "CC BY 4.0"
SUMS_FILE = "SHA256SUMS"


def build_release(
    seed: int,
    out_dir: str | Path | None = None,
    config: str | Path = RELEASE_CONFIG,
) -> Path:
    """Builds the release bundle for [seed] and returns its folder."""
    cfg = yaml.safe_load(Path(config).read_text(encoding="utf-8"))
    cfg["dataset"]["seed"] = seed
    out = Path(out_dir) if out_dir else REPO_ROOT / "data" / f"release-{seed}"
    if out.exists():
        shutil.rmtree(out)

    # The stored config keeps the relative path; the build resolves it from the repo root.
    build_cfg = json.loads(json.dumps(cfg))
    photos_dir = cfg.get("negatives", {}).get("photos_dir", "data/photos")
    if not Path(photos_dir).is_absolute():
        photos_dir = str(REPO_ROOT / photos_dir)
        build_cfg["negatives"]["photos_dir"] = photos_dir
    neg = cfg.get("negatives", {})
    uses_photos = neg.get("enabled", True) and "photo" in neg.get("types", ["photo"])
    found = len(get_valid_curated_photos(photos_dir))
    if uses_photos and found != len(PUBLIC_DOMAIN_PHOTOS):
        raise RuntimeError(
            f"{found} of {len(PUBLIC_DOMAIN_PHOTOS)} curated photos in {photos_dir}. "
            "Run `python -m ml.fetch_photos` first, so the release is the same on every machine."
        )

    # Writes images/, labels/, manifest.csv and DATASHEET.md.
    result = build_full_dataset(build_cfg, out_dir_override=out)
    weak = check_watermarks(out)
    if weak:
        raise RuntimeError(f"{len(weak)} images have no visible watermark, first: {weak[0]}")

    shutil.copytree(REPO_ROOT / "templates", out / "templates", ignore=_only_templates)
    (out / "LICENSE-DATA.md").write_text(license_text(), encoding="utf-8")

    info = {
        "seed": seed,
        "config": cfg,
        "generator_commit": _git_commit(),
        "data_license": DATA_LICENSE,
        "images": result["total_samples"],
        "min_watermark_contrast": MIN_WATERMARK_CONTRAST,
    }
    (out / "release.json").write_text(
        json.dumps(info, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    write_checksums(out)
    return out


def verify_release(bundle: str | Path) -> list[str]:
    """Rebuilds [bundle] from its own seed and config; returns the paths that differ."""
    bundle = Path(bundle)
    info = json.loads((bundle / "release.json").read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as tmp:
        cfg_path = Path(tmp) / "release.yaml"
        cfg_path.write_text(yaml.safe_dump(info["config"]), encoding="utf-8")
        rebuilt = build_release(info["seed"], Path(tmp) / "bundle", cfg_path)
        # release.json records the commit, which may differ; every data file must match.
        # Hash what is on disk, not the bundle's own list, so an edited file is caught.
        return compare_checksums(_hash_files(bundle), _read_sums(rebuilt), ignore={"release.json"})


def check_watermarks(out: Path) -> list[str]:
    """Labels of images that show a card but no watermark of at least the minimum contrast."""
    weak = []
    for label_path in sorted((out / "labels").glob("*.json")):
        label = json.loads(label_path.read_text(encoding="utf-8"))
        if label.get("is_negative"):
            continue
        contrast = label.get("watermark_contrast")
        if contrast is None or contrast < MIN_WATERMARK_CONTRAST:
            weak.append(label_path.name)
    return weak


def write_checksums(out: Path) -> None:
    lines = [f"{digest}  {path}" for path, digest in sorted(_hash_files(out).items())]
    (out / SUMS_FILE).write_text("\n".join(lines) + "\n", encoding="utf-8")


def _hash_files(out: Path) -> dict[str, str]:
    """SHA-256 of every file in [out] but the checksum list, by relative path."""
    return {
        p.relative_to(out).as_posix(): _sha256(p)
        for p in out.rglob("*")
        if p.is_file() and p.name != SUMS_FILE
    }


def compare_checksums(
    expected: dict[str, str], actual: dict[str, str], ignore: set[str] = frozenset()
) -> list[str]:
    paths = (set(expected) | set(actual)) - set(ignore)
    return sorted(p for p in paths if expected.get(p) != actual.get(p))


def license_text() -> str:
    photos = "\n".join(
        f"| `{p['filename']}` | {p['license']} | {p['description']} |" for p in PUBLIC_DOMAIN_PHOTOS
    )
    return f"""# Data license

The images, labels, manifest and datasheet in this bundle are licensed under
**Creative Commons Attribution 4.0 International ({DATA_LICENSE})**:
https://creativecommons.org/licenses/by/4.0/

Attribution: "Secure Gallery synthetic PH ID benchmark, team SUDO, NCF BSIT-3B".

The generator code that made them is a separate work under **AGPL-3.0** (the repository
license). This data license does not cover the code, and the code license does not cover
the data.

Every person, number and card in this data is fictional. Every card carries a
`SPECIMEN – NOT VALID` watermark. Do not use the data for identity verification, and do
not use it to make or pass off fake IDs (see `DATASHEET.md`, section 5).

## Third-party inputs

| Input | License | Used for |
|---|---|---|
| Liberation Sans (`ml/assets/fonts`) | SIL Open Font License 1.1 | All card and negative text |
| Faker 33.1.0 (`fil_PH` locale) | MIT | Fictional names, addresses and dates |
| qrcode | BSD | QR codes on cards |
| Pillow, NumPy, OpenCV | HPND, BSD, Apache 2.0 | Rendering, compositing, conditions |
| Card templates (`templates/`) | {DATA_LICENSE} (this bundle) | Field layout, from specimens |

Background photos for the `photo` negatives, all public domain or CC0:

| File | License | Source |
|---|---|---|
{photos}

No Roboflow or other external dataset is part of this bundle (§10.1).
"""


def _only_templates(folder: str, names: list[str]) -> set[str]:
    return {n for n in names if not (n.endswith(".json") or n.endswith(".md"))}


def _read_sums(bundle: Path) -> dict[str, str]:
    sums = {}
    for line in (bundle / SUMS_FILE).read_text(encoding="utf-8").splitlines():
        digest, path = line.split("  ", 1)
        sums[path] = digest
    return sums


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _git_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
