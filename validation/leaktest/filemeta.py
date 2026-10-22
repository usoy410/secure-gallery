"""File-level leak channels: embedded EXIF thumbnail and identifying metadata (§9.5).

Both read the redacted file itself, not its pixels: a tool can black out every field
and still ship the original picture as an EXIF thumbnail, or the phone's GPS position.
Only tag names are ever reported, never their values.
"""

from __future__ import annotations

import io
import re
from dataclasses import dataclass
from pathlib import Path

from PIL import ExifTags, Image

# IFD0 / Exif tags that identify the device or the original file.
_EXIF_TAGS = {
    0x010F: "Make",
    0x0110: "Model",
    0x010D: "DocumentName",  # TIFF: name of the scanned or original document
    0xA431: "BodySerialNumber",
    0xA435: "LensSerialNumber",
}
_GPS_POINTER = 0x8825
_THUMB_OFFSET, _THUMB_LENGTH = 0x0201, 0x0202
# XMP properties that carry edit history or the original file name.
_XMP_PROPERTIES = (
    "xmpMM:History",
    "xmpMM:DerivedFrom",
    "xmpMM:PreservedFileName",
    "xmpMM:OriginalDocumentID",
    "crs:RawFileName",
    "photoshop:History",
    "exif:GPSLatitude",
    "exif:GPSLongitude",
)


@dataclass(frozen=True)
class FileMeta:
    size: tuple[int, int]  # main image size, to scale field polygons onto the thumbnail
    thumbnail: Image.Image | None
    tags: tuple[str, ...]  # identifying tag names found, sorted


def _tiff_bytes(img: Image.Image) -> bytes:
    raw = img.info.get("exif") or b""
    return raw[6:] if raw.startswith(b"Exif\x00\x00") else raw


def _thumbnail(img: Image.Image, exif: Image.Exif) -> Image.Image | None:
    ifd1 = exif.get_ifd(ExifTags.IFD.IFD1)
    offset, length = ifd1.get(_THUMB_OFFSET), ifd1.get(_THUMB_LENGTH)
    if not offset or not length:
        return None
    data = _tiff_bytes(img)[offset : offset + length]
    try:
        thumb = Image.open(io.BytesIO(data))
        thumb.load()
    except (OSError, ValueError):
        return None
    return thumb.convert("RGB")


def _gps_present(exif: Image.Exif) -> bool:
    if _GPS_POINTER not in exif:
        return False
    try:
        return bool(exif.get_ifd(ExifTags.IFD.GPSInfo))
    except (KeyError, ValueError):
        return True  # a GPS pointer that does not parse still marks the file


def read_file_meta(path: Path) -> FileMeta:
    with Image.open(path) as img:
        exif = img.getexif()
        tags = {name for tag, name in _EXIF_TAGS.items() if str(exif.get(tag, "")).strip("\x00 ")}
        if _gps_present(exif):
            tags.add("GPSInfo")
        xmp = img.info.get("xmp") or b""
        if isinstance(xmp, bytes):
            xmp = xmp.decode("utf-8", errors="ignore")
        for prop in _XMP_PROPERTIES:
            if re.search(rf"{re.escape(prop)}\s*=|<{re.escape(prop)}[\s>]", xmp):
                tags.add(f"XMP:{prop}")
        return FileMeta(size=img.size, thumbnail=_thumbnail(img, exif), tags=tuple(sorted(tags)))


def scale_polygon(polygon: list, src: tuple[int, int], dst: tuple[int, int]) -> list:
    sx, sy = dst[0] / src[0], dst[1] / src[1]
    return [[p[0] * sx, p[1] * sy] for p in polygon]
