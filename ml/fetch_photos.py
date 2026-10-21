"""Public domain and CC0 photo fetcher and procedural fallback generator.

Provides tools to download a curated set of CC0 / Public Domain background images
into gitignored data/ directories, as well as a procedural non-network fallback
for offline testing and hermetic benchmark generation.
"""

from __future__ import annotations

import argparse
import logging
import math
import random
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFilter

logger = logging.getLogger(__name__)

# Curated list of verified Public Domain / CC0 1.0 images from Wikimedia Commons
PUBLIC_DOMAIN_PHOTOS: list[dict[str, str]] = [
    {
        "id": "photo_01",
        "filename": "desk_workspace.jpg",
        "url": (
            "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d8/"
            "Desk_workspace.jpg/1280px-Desk_workspace.jpg"
        ),
        "license": "CC0 1.0 Universal Public Domain Dedication",
        "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
        "description": "Wooden desk with laptop, notebook, and stationery items",
        "source": "Wikimedia Commons (Desk workspace)",
    },
    {
        "id": "photo_02",
        "filename": "coffee_cup.jpg",
        "url": (
            "https://upload.wikimedia.org/wikipedia/commons/thumb/4/45/"
            "A_small_cup_of_coffee.JPG/1280px-A_small_cup_of_coffee.JPG"
        ),
        "license": "CC0 1.0 Universal Public Domain Dedication",
        "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
        "description": "Small cup of coffee on saucer on wooden tabletop",
        "source": "Wikimedia Commons (A small cup of coffee)",
    },
    {
        "id": "photo_03",
        "filename": "street_scene.jpg",
        "url": (
            "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ec/"
            "Seattle_street.jpg/1280px-Seattle_street.jpg"
        ),
        "license": "Public Domain",
        "license_url": "https://creativecommons.org/publicdomain/mark/1.0/",
        "description": "Urban street corner with buildings, traffic, and pedestrians",
        "source": "Wikimedia Commons (Seattle street)",
    },
    {
        "id": "photo_04",
        "filename": "landscape_nature.jpg",
        "url": (
            "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b6/"
            "Fuji_cherry_blossom.JPG/1280px-Fuji_cherry_blossom.JPG"
        ),
        "license": "CC0 1.0 Universal Public Domain Dedication",
        "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
        "description": "Mount Fuji scenic landscape with cherry blossoms in foreground",
        "source": "Wikimedia Commons (Fuji cherry blossom)",
    },
    {
        "id": "photo_05",
        "filename": "bookshelves.jpg",
        "url": (
            "https://upload.wikimedia.org/wikipedia/commons/thumb/2/21/"
            "Biblioth%C3%A8que_de_l%27Assembl%C3%A9e_Nationale_%28Lunre%29.jpg/"
            "1280px-Biblioth%C3%A8que_de_l%27Assembl%C3%A9e_Nationale_%28Lunre%29.jpg"
        ),
        "license": "Public Domain",
        "license_url": "https://creativecommons.org/publicdomain/mark/1.0/",
        "description": "Library hall interior with wooden bookshelves and books",
        "source": "Wikimedia Commons (Bibliotheque Assemblée Nationale)",
    },
]


def fetch_cc0_photos(
    out_dir: str | Path = "data/photos",
    limit: int | None = None,
    timeout: float = 10.0,
) -> list[Path]:
    """Download curated CC0 / Public Domain photos into out_dir.

    Args:
        out_dir: Destination directory path (gitignored).
        limit: Optional maximum number of photos to fetch.
        timeout: Network socket timeout in seconds.

    Returns:
        List of Paths of downloaded or existing photo files.
    """
    dest = Path(out_dir)
    dest.mkdir(parents=True, exist_ok=True)

    items = PUBLIC_DOMAIN_PHOTOS if limit is None else PUBLIC_DOMAIN_PHOTOS[:limit]
    downloaded: list[Path] = []

    for item in items:
        file_path = dest / item["filename"]
        if file_path.exists() and file_path.stat().st_size > 1024:
            downloaded.append(file_path)
            continue

        try:
            req = urllib.request.Request(
                item["url"],
                headers={"User-Agent": "SecureGallery-SyntheticBenchmark/1.0"},
            )
            with urllib.request.urlopen(req, timeout=timeout) as resp, open(file_path, "wb") as out:
                out.write(resp.read())
            downloaded.append(file_path)
            logger.info("Downloaded public domain photo: %s", file_path.name)
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            logger.warning("Could not download photo %s: %s", item["url"], exc)

    return downloaded


def generate_procedural_photo(
    seed: int,
    width: int = 1600,
    height: int = 1200,
) -> tuple[Image.Image, dict[str, Any]]:
    """Procedurally synthesize a realistic naturalistic scene without card or ID features.

    Used when offline or when no downloaded public domain photos are present.
    Generates varied landscape, interior, or still-life compositions deterministically.

    Args:
        seed: Deterministic integer seed.
        width: Image width in pixels.
        height: Image height in pixels.

    Returns:
        Tuple of (PIL.Image, metadata_dict).
    """
    rng = random.Random(seed)
    scene_type = rng.choice(["landscape", "indoor_desk", "architectural", "nature_macro"])

    img = Image.new("RGB", (width, height), (220, 220, 220))
    draw = ImageDraw.Draw(img)

    if scene_type == "landscape":
        # Sky gradient (top to horizon)
        horizon = rng.randint(int(height * 0.45), int(height * 0.65))
        sky_top = (rng.randint(70, 120), rng.randint(140, 190), rng.randint(210, 255))
        sky_bot = (rng.randint(180, 220), rng.randint(210, 240), rng.randint(240, 255))
        for y in range(horizon):
            t = y / max(1, horizon)
            r = int(sky_top[0] * (1 - t) + sky_bot[0] * t)
            g = int(sky_top[1] * (1 - t) + sky_bot[1] * t)
            b = int(sky_top[2] * (1 - t) + sky_bot[2] * t)
            draw.line([(0, y), (width, y)], fill=(r, g, b))

        # Distant rolling hills
        hill_color = (rng.randint(60, 100), rng.randint(100, 140), rng.randint(60, 90))
        points: list[tuple[int, int]] = [(0, height)]
        step = 40
        for x in range(0, width + step, step):
            hill_y = horizon - int(
                math.sin(x * 0.005 + seed) * 80 + math.cos(x * 0.01) * 30
            )
            points.append((x, max(0, hill_y)))
        points.append((width, height))
        draw.polygon(points, fill=hill_color)

        # Ground / grass terrain with tonal variation
        ground_top = (rng.randint(80, 120), rng.randint(130, 170), rng.randint(50, 80))
        for y in range(horizon + 40, height):
            t = (y - horizon - 40) / max(1, height - horizon - 40)
            r = int(ground_top[0] * (1 - t * 0.4))
            g = int(ground_top[1] * (1 - t * 0.3))
            b = int(ground_top[2] * (1 - t * 0.2))
            draw.line([(0, y), (width, y)], fill=(r, g, b))

    elif scene_type == "indoor_desk":
        # Neutral wall background
        wall_color = (rng.randint(210, 235), rng.randint(205, 230), rng.randint(195, 220))
        draw.rectangle([0, 0, width, height], fill=wall_color)

        # Desk surface
        desk_y = rng.randint(int(height * 0.5), int(height * 0.65))
        desk_color = (rng.randint(140, 180), rng.randint(100, 140), rng.randint(60, 95))
        draw.rectangle([0, desk_y, width, height], fill=desk_color)

        # Light gradient on desk
        for dy in range(height - desk_y):
            shade = int(dy * 0.15)
            r = max(0, desk_color[0] - shade)
            g = max(0, desk_color[1] - shade)
            b = max(0, desk_color[2] - shade)
            draw.line([(0, desk_y + dy), (width, desk_y + dy)], fill=(r, g, b))

        # Stationery / mug object
        mug_cx = rng.randint(int(width * 0.2), int(width * 0.4))
        mug_cy = desk_y + rng.randint(80, 160)
        draw.ellipse(
            [mug_cx - 45, mug_cy - 45, mug_cx + 45, mug_cy + 45],
            fill=(230, 230, 235),
            outline=(180, 180, 190),
            width=2,
        )
        draw.ellipse([mug_cx - 35, mug_cy - 35, mug_cx + 35, mug_cy + 35], fill=(70, 45, 25))

    elif scene_type == "architectural":
        draw.rectangle([0, 0, width, height], fill=(135, 175, 220))
        b_x = 0
        while b_x < width:
            b_w = rng.randint(200, 400)
            b_h = rng.randint(int(height * 0.4), int(height * 0.85))
            b_color = (rng.randint(150, 210), rng.randint(140, 190), rng.randint(130, 180))
            draw.rectangle([b_x, height - b_h, b_x + b_w, height], fill=b_color)
            win_color = (rng.randint(60, 100), rng.randint(80, 130), rng.randint(110, 160))
            for wx in range(b_x + 20, b_x + b_w - 20, 40):
                for wy in range(height - b_h + 30, height - 30, 50):
                    draw.rectangle([wx, wy, wx + 20, wy + 30], fill=win_color)
            b_x += b_w + 10

    else:  # nature_macro
        bg_color = (rng.randint(50, 90), rng.randint(100, 150), rng.randint(40, 80))
        draw.rectangle([0, 0, width, height], fill=bg_color)
        for _ in range(40):
            bx = rng.randint(0, width)
            by = rng.randint(0, height)
            br = rng.randint(50, 180)
            tint = (rng.randint(120, 190), rng.randint(160, 220), rng.randint(80, 140))
            draw.ellipse([bx - br, by - br, bx + br, by + br], fill=tint)
        img = img.filter(ImageFilter.GaussianBlur(radius=rng.randint(15, 30)))

    metadata: dict[str, Any] = {
        "photo_id": f"procedural_{scene_type}_{seed:04d}",
        "scene_type": scene_type,
        "is_procedural": True,
        "source": "procedurally_generated_scene",
        "license": "CC0 1.0 Universal / Public Domain (Synthetic Procedural)",
        "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
    }
    return img, metadata


def main() -> None:
    """CLI entry point for downloading public domain photo set."""
    parser = argparse.ArgumentParser(
        description="Download curated CC0 / Public Domain photos for Secure Gallery negatives."
    )
    parser.add_argument(
        "--out",
        type=str,
        default="data/photos",
        help="Destination directory for downloaded photos (default: data/photos).",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=5,
        help="Number of photos to download (default: 5).",
    )
    args = parser.parse_args()

    files = fetch_cc0_photos(out_dir=args.out, limit=args.count)
    print(f"Retrieved {len(files)} public domain photo(s) in {args.out}")


if __name__ == "__main__":
    main()
