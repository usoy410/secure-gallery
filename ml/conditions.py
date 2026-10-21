"""Image-quality degradation conditions and geometric transformations.

Implements all 8 required image conditions from SECURE_GALLERY.md §10.1:
1. blur: Gaussian defocus with varying blur radii.
2. glare: Specular flash reflection / bright hotspot overlay.
3. rotation: In-plane rotation with bounding canvas expansion.
4. low_resolution: Downsampling and bicubic upscaling.
5. perspective: 3D projective homography simulating camera tilt angles.
6. jpeg_compression: Lossy JPEG quantization artifacts.
7. crop: Field-of-view cropping removing canvas margins/card edges.
8. occlusion: Simulated holding fingers or sticky notes masking card regions.

Each condition implements at least 3 severity levels (1=mild, 2=moderate, 3=severe).
Geometric conditions (rotation, perspective, crop) accurately transform all ground-truth
card corners and field polygon coordinates, maintaining IoU >= 0.95 with transformed masks.
"""

from __future__ import annotations

import copy
import io
import math
import random
from typing import Any

from PIL import Image, ImageDraw, ImageFilter

CONDITION_NAMES: tuple[str, ...] = (
    "blur",
    "glare",
    "rotation",
    "low_resolution",
    "perspective",
    "jpeg_compression",
    "crop",
    "occlusion",
)

SEVERITY_ALIASES: dict[str, int] = {
    "1": 1,
    "mild": 1,
    "low": 1,
    "2": 2,
    "moderate": 2,
    "medium": 2,
    "3": 3,
    "severe": 3,
    "high": 3,
}

CONDITION_ALIASES: dict[str, str] = {
    "blur": "blur",
    "glare": "glare",
    "rotation": "rotation",
    "rotate": "rotation",
    "low_resolution": "low_resolution",
    "low-resolution": "low_resolution",
    "lowres": "low_resolution",
    "resolution": "low_resolution",
    "perspective": "perspective",
    "warp": "perspective",
    "jpeg": "jpeg_compression",
    "compression": "jpeg_compression",
    "jpeg_compression": "jpeg_compression",
    "jpeg-compression": "jpeg_compression",
    "crop": "crop",
    "cropping": "crop",
    "occlusion": "occlusion",
    "occlude": "occlusion",
}


def parse_conditions_arg(conditions_arg: str | None) -> list[tuple[str, int]]:
    """Parse CLI --conditions argument string into a list of (condition, severity) tuples.

    Supports:
        - "all": all 8 conditions at severity levels 1, 2, and 3.
        - "all:1" or "all:mild": all 8 conditions at severity 1.
        - "blur,glare": blur and glare at all 3 severities.
        - "blur:1,perspective:2,crop:severe": specific condition-severity pairs.

    Args:
        conditions_arg: Comma-separated condition specification string.

    Returns:
        List of (canonical_condition_name, severity_int) tuples.
    """
    if not conditions_arg:
        return []

    tokens = [t.strip() for t in conditions_arg.split(",") if t.strip()]
    result: list[tuple[str, int]] = []
    seen: set[tuple[str, int]] = set()

    def add_pair(c: str, s: int) -> None:
        if (c, s) not in seen:
            seen.add((c, s))
            result.append((c, s))

    for token in tokens:
        parts = token.split(":", 1)
        raw_name = parts[0].strip().lower()
        if len(parts) > 1:
            sev_raw = parts[1].strip().lower()
            if not sev_raw:
                raise ValueError(
                    f"Empty severity in condition specification '{token}'."
                )
            sev_str: str | None = sev_raw
        else:
            sev_str = None

        if raw_name == "all":
            if sev_str:
                if sev_str not in SEVERITY_ALIASES:
                    raise ValueError(
                        f"Invalid severity '{sev_str}'. Use 1, 2, 3 or mild, moderate, severe."
                    )
                s_val = SEVERITY_ALIASES[sev_str]
                for c in CONDITION_NAMES:
                    add_pair(c, s_val)
            else:
                for c in CONDITION_NAMES:
                    for s in (1, 2, 3):
                        add_pair(c, s)
            continue

        if raw_name not in CONDITION_ALIASES:
            raise ValueError(
                f"Unknown condition '{raw_name}'. Available conditions: {sorted(CONDITION_NAMES)}"
            )

        canonical_name = CONDITION_ALIASES[raw_name]
        if sev_str:
            if sev_str not in SEVERITY_ALIASES:
                raise ValueError(
                    f"Invalid severity '{sev_str}'. Use 1, 2, 3 or mild, moderate, severe."
                )
            add_pair(canonical_name, SEVERITY_ALIASES[sev_str])
        else:
            # Default to all 3 severities if unspecified
            for s in (1, 2, 3):
                add_pair(canonical_name, s)

    return result


def _solve_perspective_system(
    source_points: list[tuple[int, int]],
    target_points: list[tuple[int, int]],
) -> tuple[float, ...]:
    """Solve 8-parameter homography mapping target (x, y) to source (X, Y).

    Used by PIL Image.transform with Image.Transform.PERSPECTIVE.
    """
    matrix: list[list[float]] = []
    rhs: list[float] = []
    for (X, Y), (x, y) in zip(source_points, target_points, strict=True):
        matrix.append([float(x), float(y), 1.0, 0.0, 0.0, 0.0, -float(x * X), -float(y * X)])
        rhs.append(float(X))
        matrix.append([0.0, 0.0, 0.0, float(x), float(y), 1.0, -float(x * Y), -float(y * Y)])
        rhs.append(float(Y))

    n = 8
    for i in range(n):
        max_row = max(range(i, n), key=lambda r: abs(matrix[r][i]))
        matrix[i], matrix[max_row] = matrix[max_row], matrix[i]
        rhs[i], rhs[max_row] = rhs[max_row], rhs[i]

        pivot = matrix[i][i]
        if abs(pivot) < 1e-9:
            raise ValueError("Singular matrix in perspective solve")

        for j in range(i, n):
            matrix[i][j] /= pivot
        rhs[i] /= pivot

        for r in range(n):
            if r != i:
                factor = matrix[r][i]
                for j in range(i, n):
                    matrix[r][j] -= factor * matrix[i][j]
                rhs[r] -= factor * rhs[i]

    return tuple(rhs)


def _apply_forward_homography(
    pt: list[int] | tuple[int, int],
    coeffs: tuple[float, ...],
) -> list[int]:
    """Map a point forward using 8 homography coefficients."""
    a, b, c, d, e, f, g, h = coeffs
    X, Y = pt[0], pt[1]
    denom = g * X + h * Y + 1.0
    x = (a * X + b * Y + c) / denom
    y = (d * X + e * Y + f) / denom
    return [int(round(x)), int(round(y))]


# ---------------------------------------------------------------------------
# Individual Condition Implementations
# ---------------------------------------------------------------------------


def apply_blur(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply Gaussian blur condition with 3 severity levels.

    Why: Simulates camera motion defocus and camera shake common in real mobile captures.
    """
    radii = {1: 2.0, 2: 5.0, 3: 9.0}
    radius = radii.get(severity, 2.0)
    blurred = img.filter(ImageFilter.GaussianBlur(radius=radius))
    return blurred, label


def apply_glare(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply specular flash / glare hotspot with 3 severity levels.

    Why: Simulates bright camera flash reflections on laminated or polycarbonate card surfaces.
    """
    rng = random.Random(seed ^ 0x676C6172)
    w, h = img.size

    # Glare radii and peak alpha levels per severity
    config = {
        1: (int(min(w, h) * 0.15), 110),
        2: (int(min(w, h) * 0.25), 180),
        3: (int(min(w, h) * 0.38), 245),
    }
    radius, peak_alpha = config.get(severity, config[1])

    # Position glare near center or over card area
    corners = label.get("card_corners")
    if corners:
        xs = [pt[0] for pt in corners]
        ys = [pt[1] for pt in corners]
        card_cx = (min(xs) + max(xs)) // 2
        card_cy = (min(ys) + max(ys)) // 2
    else:
        card_cx, card_cy = w // 2, h // 2

    gx = card_cx + rng.randint(-w // 8, w // 8)
    gy = card_cy + rng.randint(-h // 8, h // 8)

    # Render radial glare wash
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    steps = 24
    for i in range(steps, 0, -1):
        r_step = int(radius * (i / steps))
        # Quadratic alpha falloff for realistic specular reflection
        alpha = int(peak_alpha * ((steps - i + 1) / steps) ** 1.8)
        c = (255, 255, 255, alpha)
        draw.ellipse([gx - r_step, gy - r_step, gx + r_step, gy + r_step], fill=c)

    composite = Image.alpha_composite(img.convert("RGBA"), overlay)
    return composite.convert("RGB"), label


def apply_rotation(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply in-plane rotation with expand=True, transforming all polygon coordinates.

    Why: Users capture ID cards at diverse camera angles requiring rotation invariance.
    """
    rng = random.Random(seed ^ 0x726F7461)
    angles = {1: 6.0, 2: 15.0, 3: 32.0}
    base_angle = angles.get(severity, 6.0)
    sign = -1.0 if rng.random() < 0.5 else 1.0
    angle_deg = sign * (base_angle + rng.uniform(-1.0, 1.0))

    w, h = img.size
    rotated_img = img.rotate(angle_deg, expand=True, resample=Image.Resampling.BICUBIC)
    rw, rh = rotated_img.size

    # Exact rotation math matching PIL rotate(angle_deg, expand=True)
    rad = math.radians(angle_deg)
    cos_a = math.cos(rad)
    sin_a = math.sin(rad)
    cx = (w - 1) / 2.0
    cy = (h - 1) / 2.0
    rcx = (rw - 1) / 2.0
    rcy = (rh - 1) / 2.0

    def transform_pt(pt: list[int] | tuple[int, int]) -> list[int]:
        dx = pt[0] - cx
        dy = pt[1] - cy
        nx = dx * cos_a + dy * sin_a + rcx
        ny = -dx * sin_a + dy * cos_a + rcy
        return [int(round(nx)), int(round(ny))]

    new_label = copy.deepcopy(label)
    new_label["image_size"] = [rw, rh]

    if "card_corners" in new_label:
        new_label["card_corners"] = [transform_pt(pt) for pt in new_label["card_corners"]]

    for field in new_label.get("fields", []):
        field["polygon"] = [transform_pt(pt) for pt in field["polygon"]]

    return rotated_img, new_label


def apply_low_resolution(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply low-resolution downsampling and upscaling with 3 severity levels.

    Why: Low-end smartphone cameras or messaging app downscaling significantly degrade small text.
    """
    factors = {1: 2, 2: 4, 3: 8}
    scale_factor = factors.get(severity, 2)
    w, h = img.size

    down_w = max(16, w // scale_factor)
    down_h = max(16, h // scale_factor)

    small = img.resize((down_w, down_h), Image.Resampling.BILINEAR)
    restored = small.resize((w, h), Image.Resampling.BILINEAR)
    return restored, label


def apply_perspective(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply 3D perspective warp and update all polygons via forward homography.

    Why: Photos taken from non-perpendicular vantage points produce keystoning effects.
    """
    rng = random.Random(seed ^ 0x70657273)
    w, h = img.size

    # Corner displacement ratios per severity
    ratios = {1: 0.05, 2: 0.10, 3: 0.18}
    ratio = ratios.get(severity, 0.05)

    dx_val = int(w * ratio)
    dy_val = int(h * ratio)

    # 4 distinct tilt orientations: top tilt, bottom tilt, left tilt, right tilt
    mode = rng.randint(0, 3)
    src_corners = [(0, 0), (w, 0), (w, h), (0, h)]

    if mode == 0:  # Top tilt
        dst_corners = [(dx_val, dy_val), (w - dx_val, dy_val), (w, h), (0, h)]
    elif mode == 1:  # Bottom tilt
        dst_corners = [(0, 0), (w, 0), (w - dx_val, h - dy_val), (dx_val, h - dy_val)]
    elif mode == 2:  # Left tilt
        dst_corners = [(dx_val, dy_val), (w, 0), (w, h), (dx_val, h - dy_val)]
    else:  # Right tilt
        dst_corners = [(0, 0), (w - dx_val, dy_val), (w - dx_val, h - dy_val), (0, h)]

    # pil_coeffs maps target -> source; fwd_coeffs maps source -> target
    pil_coeffs = _solve_perspective_system(src_corners, dst_corners)
    fwd_coeffs = _solve_perspective_system(dst_corners, src_corners)

    warped_img = img.transform(
        (w, h),
        Image.Transform.PERSPECTIVE,
        pil_coeffs,
        resample=Image.Resampling.BICUBIC,
    )

    new_label = copy.deepcopy(label)
    new_label["image_size"] = [w, h]

    if "card_corners" in new_label:
        new_label["card_corners"] = [
            _apply_forward_homography(pt, fwd_coeffs) for pt in new_label["card_corners"]
        ]

    for field in new_label.get("fields", []):
        field["polygon"] = [
            _apply_forward_homography(pt, fwd_coeffs) for pt in field["polygon"]
        ]

    return warped_img, new_label


def clip_polygon_to_rect(
    polygon: list[list[int]] | list[tuple[int, int]],
    xmin: int,
    ymin: int,
    xmax: int,
    ymax: int,
) -> list[list[int]]:
    """Clip a 2D polygon against an axis-aligned bounding box using Sutherland-Hodgman.

    Why: When applying field-of-view crops, card fields near the cropped edges must be
    clipped to the visible image bounds [0, 0, width, height]. Fields outside the crop
    reduce to empty or degenerate polygons and are dropped from ground-truth labels.

    Args:
        polygon: List of (x, y) coordinate pairs defining the polygon.
        xmin: Minimum x-coordinate (inclusive boundary).
        ymin: Minimum y-coordinate (inclusive boundary).
        xmax: Maximum x-coordinate (inclusive boundary).
        ymax: Maximum y-coordinate (inclusive boundary).

    Returns:
        List of integer [x, y] coordinates for the clipped polygon, or empty list if disjoint.
    """
    def clip_edge(
        poly: list[list[float]],
        inside_fn: Any,
        intersect_fn: Any,
    ) -> list[list[float]]:
        output: list[list[float]] = []
        if not poly:
            return output
        s = poly[-1]
        for p in poly:
            if inside_fn(p):
                if not inside_fn(s):
                    output.append(intersect_fn(s, p))
                output.append(p)
            elif inside_fn(s):
                output.append(intersect_fn(s, p))
            s = p
        return output

    poly = [[float(p[0]), float(p[1])] for p in polygon]
    # 1. Left boundary: x >= xmin
    poly = clip_edge(
        poly,
        lambda p: p[0] >= xmin,
        lambda s, p: [float(xmin), s[1] + (xmin - s[0]) * (p[1] - s[1]) / (p[0] - s[0])],
    )
    # 2. Right boundary: x <= xmax
    poly = clip_edge(
        poly,
        lambda p: p[0] <= xmax,
        lambda s, p: [float(xmax), s[1] + (xmax - s[0]) * (p[1] - s[1]) / (p[0] - s[0])],
    )
    # 3. Top boundary: y >= ymin
    poly = clip_edge(
        poly,
        lambda p: p[1] >= ymin,
        lambda s, p: [s[0] + (ymin - s[1]) * (p[0] - s[0]) / (p[1] - s[1]), float(ymin)],
    )
    # 4. Bottom boundary: y <= ymax
    poly = clip_edge(
        poly,
        lambda p: p[1] <= ymax,
        lambda s, p: [s[0] + (ymax - s[1]) * (p[0] - s[0]) / (p[1] - s[1]), float(ymax)],
    )

    result: list[list[int]] = []
    for pt in poly:
        x = max(int(xmin), min(int(xmax), int(round(pt[0]))))
        y = max(int(ymin), min(int(ymax), int(round(pt[1]))))
        if not result or result[-1] != [x, y]:
            result.append([x, y])
    if len(result) > 1 and result[0] == result[-1]:
        result.pop()

    return result


def polygon_area(polygon: list[list[int]] | list[tuple[int, int]]) -> float:
    """Compute the 2D planar area of a polygon using the Shoelace formula.

    Why: Used to determine whether a clipped polygon retains non-zero visible area,
    allowing fields completely clipped out of frame to be discarded from the label.

    Args:
        polygon: List of [x, y] coordinates.

    Returns:
        Area as float >= 0.0.
    """
    if len(polygon) < 3:
        return 0.0
    n = len(polygon)
    area = 0.0
    for i in range(n):
        j = (i + 1) % n
        area += polygon[i][0] * polygon[j][1] - polygon[j][0] * polygon[i][1]
    return 0.5 * abs(area)


def apply_jpeg_compression(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply lossy JPEG compression artifacts with 3 quality levels.

    Why: Camera codecs and gallery re-compressions introduce blocking and high-frequency ringing.
    """
    qualities = {1: 50, 2: 25, 3: 10}
    quality = qualities.get(severity, 50)

    buf = io.BytesIO()
    # JPEG format does not support alpha channel (RGBA), so convert to RGB first
    save_img = img.convert("RGB") if img.mode != "RGB" else img
    save_img.save(buf, format="JPEG", quality=quality)
    buf.seek(0)
    degraded = Image.open(buf).convert("RGB")
    return degraded, label


def apply_crop(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply cropping condition and translate/clip all polygon coordinates.

    Why: Partial card framing occurs when users do not capture the entire card in frame.
    Field polygons are clipped to the crop viewport, and fields with zero visible area
    are omitted from the label so only visible fields remain.
    """
    rng = random.Random(seed ^ 0x63726F70)
    w, h = img.size

    # Crop edge fractions per severity level
    crop_fractions = {1: 0.05, 2: 0.12, 3: 0.22}
    frac = crop_fractions.get(severity, 0.05)

    x1 = int(round(w * rng.uniform(frac * 0.6, frac * 1.2)))
    y1 = int(round(h * rng.uniform(frac * 0.6, frac * 1.2)))
    x2 = w - int(round(w * rng.uniform(frac * 0.6, frac * 1.2)))
    y2 = h - int(round(h * rng.uniform(frac * 0.6, frac * 1.2)))

    # Ensure valid bounding box
    x1 = max(0, min(x1, w // 4))
    y1 = max(0, min(y1, h // 4))
    x2 = min(w, max(x2, 3 * w // 4))
    y2 = min(h, max(y2, 3 * h // 4))

    cropped_img = img.crop((x1, y1, x2, y2))
    cw, ch = cropped_img.size

    new_label = copy.deepcopy(label)
    new_label["image_size"] = [cw, ch]

    def transform_pt(pt: list[int] | tuple[int, int]) -> list[int]:
        return [pt[0] - x1, pt[1] - y1]

    if "card_corners" in new_label:
        new_label["card_corners"] = [transform_pt(pt) for pt in new_label["card_corners"]]

    visible_fields: list[dict[str, Any]] = []
    for field in new_label.get("fields", []):
        trans_poly = [transform_pt(pt) for pt in field["polygon"]]
        clipped_poly = clip_polygon_to_rect(trans_poly, 0, 0, cw, ch)
        if len(clipped_poly) >= 3 and polygon_area(clipped_poly) > 0:
            field["polygon"] = clipped_poly
            visible_fields.append(field)

    new_label["fields"] = visible_fields

    return cropped_img, new_label


def apply_occlusion(
    img: Image.Image,
    label: dict[str, Any],
    severity: int,
    seed: int,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply simulated holding finger or card occluding patch with 3 severity levels.

    Why: Real-life ID photos often feature fingers or card holders obstructing card text/photo.
    """
    rng = random.Random(seed ^ 0x6F63636C)
    w, h = img.size

    # Occlusion patch dimensions per severity
    dim_factors = {1: 0.08, 2: 0.16, 3: 0.26}
    factor = dim_factors.get(severity, 0.08)

    occ_w = int(w * factor)
    occ_h = int(h * factor)

    # Position occlusion over card area or edge
    corners = label.get("card_corners")
    if corners:
        xs = [pt[0] for pt in corners]
        ys = [pt[1] for pt in corners]
        target_x = rng.choice(xs)
        target_y = rng.choice(ys)
    else:
        target_x = w // 2
        target_y = h // 2

    ox0 = max(0, min(w - occ_w, target_x - occ_w // 2))
    oy0 = max(0, min(h - occ_h, target_y - occ_h // 2))
    ox1 = ox0 + occ_w
    oy1 = oy0 + occ_h

    img_out = img.copy()
    draw = ImageDraw.Draw(img_out)

    # Two realistic styles: flesh tone finger/thumb or paper sticky note
    style = rng.choice(["finger", "sticky_note"])
    if style == "finger":
        finger_color = (rng.randint(210, 230), rng.randint(160, 180), rng.randint(130, 150))
        draw.ellipse([ox0, oy0, ox1, oy1], fill=finger_color, outline=(180, 130, 100), width=1)
    else:
        note_color = (rng.randint(245, 255), rng.randint(235, 250), rng.randint(140, 160))
        draw.rounded_rectangle(
            [ox0, oy0, ox1, oy1],
            radius=6,
            fill=note_color,
            outline=(200, 180, 110),
            width=1,
        )

    return img_out, label


CONDITION_HANDLERS = {
    "blur": apply_blur,
    "glare": apply_glare,
    "rotation": apply_rotation,
    "low_resolution": apply_low_resolution,
    "perspective": apply_perspective,
    "jpeg_compression": apply_jpeg_compression,
    "crop": apply_crop,
    "occlusion": apply_occlusion,
}


def apply_condition(
    img: Image.Image,
    label: dict[str, Any],
    condition: str,
    severity: int | str = 1,
    seed: int = 42,
) -> tuple[Image.Image, dict[str, Any]]:
    """Apply a named parameterized condition to an image and record metadata in the label.

    Why: Acceptance criteria requires recording condition name, severity, and base image id
    in the ground-truth label dictionary.

    Args:
        img: Source PIL Image.
        label: Ground truth label dictionary.
        condition: Condition name (e.g. 'blur', 'rotation', 'perspective').
        severity: Severity level (1, 2, 3 or 'mild', 'moderate', 'severe').
        seed: Deterministic integer seed.

    Returns:
        Tuple of (degraded_image, updated_label_dict).
    """
    canonical_cond = CONDITION_ALIASES.get(condition.lower())
    if not canonical_cond or canonical_cond not in CONDITION_HANDLERS:
        raise ValueError(f"Unknown condition '{condition}'. Available: {sorted(CONDITION_NAMES)}")

    sev_int = SEVERITY_ALIASES.get(str(severity).lower())
    if sev_int is None or sev_int not in (1, 2, 3):
        raise ValueError(f"Invalid severity '{severity}'. Must be 1, 2, or 3.")

    base_id = label.get("base_image_id") or label.get("id") or label.get("variant", "sample")

    handler = CONDITION_HANDLERS[canonical_cond]
    transformed_img, transformed_label = handler(img, label, sev_int, seed)

    # Ensure label dictionary is deeply copied and updated with condition metadata
    final_label = copy.deepcopy(transformed_label)
    final_label["condition"] = canonical_cond
    final_label["severity"] = sev_int
    final_label["base_image_id"] = base_id

    return transformed_img, final_label
