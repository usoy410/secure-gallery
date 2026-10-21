"""Generate labeled synthetic card images from the templates in ``templates/``."""

from __future__ import annotations

import hashlib
import json
import random
from pathlib import Path
from typing import Any

from PIL import Image

from ml.generate.compose import apply, compose
from ml.generate.render import card_size, render_card
from ml.generate.values import IMAGE_FIELDS, make_values

TEMPLATES_DIR = Path(__file__).resolve().parents[2] / "templates"
LABEL_VERSION = 1


def load_templates(templates_dir: Path = TEMPLATES_DIR) -> dict[str, dict[str, Any]]:
    """All card templates keyed by variant id."""
    templates = {}
    for path in sorted(templates_dir.glob("*.json")):
        if path.name == "schema.json":
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        templates[data["id"]] = data
    return templates


def image_seed(seed: int, variant: str, index: int) -> int:
    """Stable per-image seed, independent of Python's randomized hash()."""
    digest = hashlib.sha256(f"{seed}:{variant}:{index}".encode()).digest()
    return int.from_bytes(digest[:8], "big")


def generate_sample(
    template: dict[str, Any], seed: int
) -> tuple[Image.Image, Image.Image, dict[str, Any]]:
    """Return (scene image, flat card, label) for one synthetic card."""
    rng = random.Random(seed)
    values = make_values(template["document_class"], rng.getrandbits(32))
    card = render_card(template, values, rng)
    scene, matrix, background = compose(card, rng)

    w, h = card_size(template)
    corners = apply(matrix, [(0, 0), (w, 0), (w, h), (0, h)])
    fields = []
    for field in template["fields"]:
        poly = apply(matrix, [(x * w, y * h) for x, y in field["polygon"]])
        fields.append({
            "name": field["name"],
            "type": field["type"],
            "sensitive": field["sensitive"],
            "polygon": [[round(x, 2), round(y, 2)] for x, y in poly],
            "value": None if field["type"] in IMAGE_FIELDS else values[field["type"]],
        })  # fmt: skip
    label = {
        "label_version": LABEL_VERSION,
        "synthetic": True,
        "document_class": template["document_class"],
        "variant": template["id"],
        "seed": seed,
        "width": scene.width,
        "height": scene.height,
        "background": background,
        "card_corners": [[round(x, 2), round(y, 2)] for x, y in corners],
        "fields": fields,
    }
    return scene, card, label


def write_dataset(variant: str, count: int, seed: int, out: Path) -> list[Path]:
    """Write ``count`` PNG images and JSON labels per variant under ``out/<variant>/``.

    ``variant`` may be "all" to cover every template.
    """
    templates = load_templates()
    if variant == "all":
        chosen = list(templates.values())
    elif variant in templates:
        chosen = [templates[variant]]
    else:
        raise SystemExit(f"Unknown variant {variant!r}. Choose from: all, {', '.join(templates)}")

    written = []
    for template in chosen:
        folder = out / template["id"]
        folder.mkdir(parents=True, exist_ok=True)
        for index in range(count):
            stem = f"{template['id']}-s{seed}-{index:05d}"
            scene, _, label = generate_sample(template, image_seed(seed, template["id"], index))
            label["image"] = f"{stem}.png"
            scene.save(folder / f"{stem}.png", format="PNG")
            text = json.dumps(label, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
            (folder / f"{stem}.json").write_text(text, encoding="utf-8")
            written.append(folder / f"{stem}.png")
    return written
