"""Photos for the on-device redaction test (#18), with the metadata a real phone photo carries.

Writes 20 generated card photos (4 per front-side template, seeds outside the training set) to
`android/app/src/androidTest/assets/redaction/`, each with its ground-truth label as
`<id>.json`. The emulator test runs the whole pipeline on them and exports the redacted
copies; CI then runs the leak test (D11) and the file-metadata check (D13) against these
labels.

Every photo carries EXIF the copy must not keep: camera make and model, a GPS position and a
thumbnail of the unredacted card. It also carries a capture date the copy must keep. Every
fourth photo is stored rotated with an orientation tag, so the copy must apply the rotation
to its pixels. Labels are always in upright coordinates.

Usage: python -m ml.android_redaction_fixtures
"""

from __future__ import annotations

import io
import json
import struct
from pathlib import Path

from PIL import Image

from ml.generate import generate_single_sample
from templates.validate import load_json

REPO_ROOT = Path(__file__).resolve().parents[1]
OUT = REPO_ROOT / "android" / "app" / "src" / "androidTest" / "assets" / "redaction"
TEMPLATES = (
    "philsys-card",
    "philsys-ephilid",
    "lto-dl-current",
    "lto-dl-previous",
    "payment-card-front",
)
PER_TEMPLATE = 4
FIRST_SEED = 92001
JPEG_QUALITY = 85
THUMBNAIL_WIDTH = 160
ORIENTATION_ROTATE_90 = 6  # stored turned left; viewers turn it 90 degrees clockwise


def _ascii(text: str) -> bytes:
    return text.encode("ascii") + b"\0"


def _rational(value: float) -> bytes:
    return struct.pack("<II", round(value * 10000), 10000)


def exif_block(date: str, orientation: int, thumbnail: bytes) -> bytes:
    """Little-endian EXIF: make, model, orientation, a capture date, GPS and a JPEG thumbnail."""
    # Each IFD is (tag, type, count, payload); payloads over 4 bytes go after the IFD.
    ifd0 = [
        (0x010F, 2, _ascii("Synthetic Phone Co")),
        (0x0110, 2, _ascii("SP-1")),
        (0x0112, 3, struct.pack("<HH", orientation, 0)),
        (0x8769, 4, None),  # Exif IFD pointer, filled in below
        (0x8825, 4, None),  # GPS IFD pointer
    ]
    exif_ifd = [(0x9003, 2, _ascii(date)), (0x9011, 2, _ascii("+08:00"))]
    gps_ifd = [
        (0x0001, 2, _ascii("N")),
        (0x0002, 5, _rational(14) + _rational(35) + _rational(58.2)),
        (0x0003, 2, _ascii("E")),
        (0x0004, 5, _rational(120) + _rational(59) + _rational(3.1)),
    ]
    ifd1 = [
        (0x0103, 3, struct.pack("<HH", 6, 0)),
        (0x0201, 4, None),
        (0x0202, 4, struct.pack("<I", len(thumbnail))),
    ]

    def size(ifd: list) -> int:
        extra = sum(len(p) for _, _, p in ifd if p is not None and len(p) > 4)
        return 2 + 12 * len(ifd) + 4 + extra

    off0 = 8
    off_exif = off0 + size(ifd0)
    off_gps = off_exif + size(exif_ifd)
    off_ifd1 = off_gps + size(gps_ifd)
    off_thumb = off_ifd1 + size(ifd1)
    pointers = {0x8769: off_exif, 0x8825: off_gps, 0x0201: off_thumb}

    def pack(ifd: list, offset: int, next_ifd: int) -> bytes:
        out = struct.pack("<H", len(ifd))
        data = b""
        data_at = offset + 2 + 12 * len(ifd) + 4
        for tag, kind, payload in ifd:
            if payload is None:
                payload = struct.pack("<I", pointers[tag])
            unit = {2: 1, 3: 2, 4: 4, 5: 8}[kind]
            count = len(payload) // unit if kind != 3 else 1
            if len(payload) > 4:
                out += struct.pack("<HHII", tag, kind, count, data_at + len(data))
                data += payload
            else:
                out += struct.pack("<HHI", tag, kind, count) + payload.ljust(4, b"\0")
        return out + struct.pack("<I", next_ifd) + data

    tiff = b"II*\0" + struct.pack("<I", off0)
    tiff += pack(ifd0, off0, off_ifd1)
    tiff += pack(exif_ifd, off_exif, 0)
    tiff += pack(gps_ifd, off_gps, 0)
    tiff += pack(ifd1, off_ifd1, 0)
    return b"Exif\0\0" + tiff + thumbnail


def make_fixtures(out_dir: Path = OUT) -> list[str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    ids = []
    for i in range(len(TEMPLATES) * PER_TEMPLATE):
        template_id = TEMPLATES[i % len(TEMPLATES)]
        seed = FIRST_SEED + i
        image_id = f"r{i + 1:02d}_{template_id}"
        img, label = generate_single_sample(
            load_json(REPO_ROOT / "templates" / f"{template_id}.json"), seed=seed, composite=True
        )
        img = img.convert("RGB")

        thumb = img.resize((THUMBNAIL_WIDTH, round(img.height * THUMBNAIL_WIDTH / img.width)))
        buf = io.BytesIO()
        thumb.save(buf, "JPEG", quality=80)
        rotated = i % 4 == 3
        stored = img.transpose(Image.Transpose.ROTATE_90) if rotated else img
        exif = exif_block(
            f"2024:05:{6 + i % 20:02d} 07:08:09",
            ORIENTATION_ROTATE_90 if rotated else 1,
            buf.getvalue(),
        )
        stored.save(out_dir / f"{image_id}.jpg", "JPEG", quality=JPEG_QUALITY, exif=exif)

        label["seed"] = seed
        label["stored_rotated"] = rotated
        (out_dir / f"{image_id}.json").write_text(
            json.dumps(label, indent=1) + "\n", encoding="utf-8"
        )
        ids.append(image_id)
    return ids


if __name__ == "__main__":
    written = make_fixtures()
    print(f"Wrote {len(written)} photos and labels to {OUT}")
