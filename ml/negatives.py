"""Negative sample generator for Secure Gallery benchmark.

Generates 6 distinct categories of negative non-ID samples:
1. 'receipt': Synthetic store receipts with thermal receipt typography and items.
2. 'loyalty_card': Retail/cafe loyalty and membership cards (ID-1 form factor without ID data).
3. 'blank_card': Unprinted plastic PVC cards (CR80 standard) with bevel/shadow.
4. 'card_rectangle': Card-shaped rectangular geometries (sticky notes, coasters, business cards).
5. 'screenshot': Mobile phone screenshots (chat conversation, notes, settings screen).
6. 'photo': Everyday scene photos from CC0 public-domain sources or procedural fallback.

All negative samples produce ground-truth JSON labels with class='negative' and fields=[].
"""

from __future__ import annotations

import logging
import math
import random
from pathlib import Path
from typing import Any

from faker import Faker
from PIL import Image, ImageDraw

from ml.composite import (
    CANVAS_HEIGHT,
    CANVAS_WIDTH,
    create_hand_held_background,
    create_table_background,
)
from ml.fetch_photos import (
    PUBLIC_DOMAIN_PHOTOS,
    generate_procedural_photo,
)
from ml.fonts import get_font

logger = logging.getLogger(__name__)

NEGATIVE_TYPES: tuple[str, ...] = (
    "receipt",
    "loyalty_card",
    "blank_card",
    "card_rectangle",
    "screenshot",
    "photo",
)

# Realistic Philippine retail and grocery stores for receipts
PHILIPPINE_STORES: list[str] = [
    "SM SUPERMARKET",
    "ROBINSONS SUPERMARKET",
    "PUREGOLD PRICE CLUB",
    "MERCURY DRUG",
    "7-ELEVEN PHILIPPINES",
    "MINISTOP CONVENIENCE",
    "ALFAMART PHILIPPINES",
    "METRO RETAIL STORE",
]

PHILIPPINE_GROCERY_ITEMS: list[tuple[str, float]] = [
    ("SAN MIG LIGHT CAN 330ML", 56.50),
    ("BEARING DOG SOAP 100G", 88.00),
    ("LUCKY ME PANCIT CANTON 80G", 17.25),
    ("NESTLE FRESH MILK 1L", 112.00),
    ("BEAR BRAND POWDER 320G", 145.50),
    ("SILVER SWAN SOY SAUCE 385ML", 24.00),
    ("DATU PUTI VINEGAR 385ML", 22.50),
    ("MAGGI MAGIC SARAP 12X8G", 65.00),
    ("SELECTA VANILLA ICE CREAM 1.3L", 245.00),
    ("GREAT TASTE WHITE 10X30G", 78.00),
    ("GARDENIA WHITE BREAD 400G", 68.00),
    ("SAFEGUARD WHITE SOAP 135G", 54.00),
]


def generate_receipt_image(seed: int, width: int = 500, height: int = 900) -> Image.Image:
    """Generate a realistic synthetic thermal paper receipt image.

    Args:
        seed: Deterministic integer seed.
        width: Receipt paper width.
        height: Receipt paper height.

    Returns:
        Pillow RGBA Image of receipt paper with jagged bottom cut.
    """
    rng = random.Random(seed)
    paper_color = (rng.randint(245, 252), rng.randint(245, 250), rng.randint(240, 246))
    receipt_img = Image.new("RGB", (width, height), paper_color)
    draw = ImageDraw.Draw(receipt_img)

    font_title = get_font(size=22, bold=True)
    font_bold = get_font(size=16, bold=True)
    font_regular = get_font(size=14, bold=False)
    font_small = get_font(size=12, bold=False)

    text_color = (rng.randint(20, 50), rng.randint(20, 50), rng.randint(20, 50))

    store_name = rng.choice(PHILIPPINE_STORES)
    branch = f"BRANCH #{rng.randint(101, 899)} - MANILA"
    tin = f"TIN: {rng.randint(100, 999)}-{rng.randint(100, 999)}-{rng.randint(100, 999)}-000"
    pos = f"POS: {rng.randint(1, 15):02d} | CASHIER: {rng.randint(1000, 9999)}"
    dt_str = (
        f"DATE: 2026-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d} "
        f"{rng.randint(8, 21):02d}:{rng.randint(10, 59):02d}"
    )

    y = 30
    header_rows = [
        (store_name, font_title),
        (branch, font_small),
        (tin, font_small),
        (pos, font_small),
        (dt_str, font_small),
    ]
    for line, fnt in header_rows:
        bbox = draw.textbbox((0, 0), line, font=fnt)
        lw = bbox[2] - bbox[0]
        draw.text(((width - lw) // 2, y), line, fill=text_color, font=fnt)
        y += (bbox[3] - bbox[1]) + 6

    # Separator line
    y += 10
    draw.line([(25, y), (width - 25, y)], fill=text_color, width=1)
    y += 15

    num_items = rng.randint(4, 7)
    selected_items = rng.sample(PHILIPPINE_GROCERY_ITEMS, num_items)
    subtotal = 0.0

    for item_name, price in selected_items:
        qty = rng.randint(1, 3)
        total_price = qty * price
        subtotal += total_price

        display_name = item_name[:24]
        draw.text((25, y), f"{qty}x {display_name}", fill=text_color, font=font_regular)
        price_str = f"{total_price:.2f}"
        p_bbox = draw.textbbox((0, 0), price_str, font=font_regular)
        pw = p_bbox[2] - p_bbox[0]
        draw.text((width - 25 - pw, y), price_str, fill=text_color, font=font_regular)
        y += 24

    y += 10
    draw.line([(25, y), (width - 25, y)], fill=text_color, width=1)
    y += 15

    vat = subtotal * 0.12
    total = subtotal + vat
    cash = math.ceil(total / 100.0) * 100.0
    change = cash - total

    totals = [
        ("SUBTOTAL", subtotal),
        ("12% VAT", vat),
        ("TOTAL AMOUNT", total),
        ("CASH", cash),
        ("CHANGE", change),
    ]
    for label, val in totals:
        is_total = label == "TOTAL AMOUNT"
        fnt = font_bold if is_total else font_regular
        draw.text((25, y), label, fill=text_color, font=fnt)
        val_str = f"PHP {val:.2f}"
        v_bbox = draw.textbbox((0, 0), val_str, font=fnt)
        vw = v_bbox[2] - v_bbox[0]
        draw.text((width - 25 - vw, y), val_str, fill=text_color, font=fnt)
        y += 26 if is_total else 20

    y += 15
    draw.line([(25, y), (width - 25, y)], fill=text_color, width=1)
    y += 20

    bar_x = 50
    bar_w = width - 100
    for bx in range(bar_x, bar_x + bar_w, 4):
        if rng.random() > 0.35:
            draw.line([(bx, y), (bx, y + 45)], fill=text_color, width=rng.choice([1, 2, 3]))
    y += 55

    for footer_line in ["OFFICIAL RECEIPT", "THANK YOU FOR SHOPPING WITH US!"]:
        f_bbox = draw.textbbox((0, 0), footer_line, font=font_small)
        fw = f_bbox[2] - f_bbox[0]
        draw.text(((width - fw) // 2, y), footer_line, fill=text_color, font=font_small)
        y += 18

    # Jagged receipt bottom edge
    jagged_mask = Image.new("L", (width, height), 255)
    j_draw = ImageDraw.Draw(jagged_mask)
    cut_y = min(y + 25, height - 15)
    j_points = [(0, cut_y)]
    for jx in range(0, width, 12):
        jy = cut_y + (6 if (jx // 12) % 2 == 0 else 0)
        j_points.append((jx, jy))
    j_points.extend([(width, height), (0, height)])
    j_draw.polygon(j_points, fill=0)

    receipt_img.putalpha(jagged_mask)
    return receipt_img


def generate_loyalty_card_image(seed: int, width: int = 1000, height: int = 630) -> Image.Image:
    """Generate a retail loyalty / membership card image (ID-1 form factor).

    Args:
        seed: Deterministic integer seed.
        width: Card pixel width.
        height: Card pixel height.

    Returns:
        Pillow RGBA Image of loyalty card with rounded corners.
    """
    rng = random.Random(seed)
    faker = Faker("fil_PH")
    faker.seed_instance(seed)

    themes = [
        ("CAFE PERKS CLUB", (45, 30, 20), (210, 160, 95)),
        ("SUPERMARKET REWARDS", (15, 60, 110), (255, 200, 50)),
        ("ANYTIME FITNESS PASS", (30, 30, 35), (220, 50, 40)),
        ("METRO CINEMA CLUB", (60, 15, 45), (230, 190, 70)),
        ("VIP LOYALTY PRIVILEGE", (20, 35, 40), (180, 220, 210)),
    ]
    title, bg_primary, accent = rng.choice(themes)

    card = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(card)

    corner_radius = 32
    draw.rounded_rectangle([0, 0, width, height], radius=corner_radius, fill=bg_primary)

    accent_points = [
        (0, int(height * 0.45)),
        (int(width * 0.4), int(height * 0.35)),
        (width, int(height * 0.6)),
        (width, height),
        (0, height),
    ]
    draw.polygon(accent_points, fill=accent)

    font_logo = get_font(size=36, bold=True)
    draw.text((60, 55), title, fill=(255, 255, 255), font=font_logo)

    font_badge = get_font(size=20, bold=True)
    tier = rng.choice(["GOLD MEMBER", "PLATINUM VIP", "CLASSIC REWARDS", "DIAMOND CLUB"])
    draw.rounded_rectangle([60, 120, 280, 160], radius=8, fill=(0, 0, 0, 100))
    draw.text((75, 128), tier, fill=(255, 255, 255), font=font_badge)

    font_name = get_font(size=28, bold=True)
    font_label = get_font(size=16, bold=False)
    member_name = f"{faker.first_name().upper()} {faker.last_name().upper()}"
    member_id = f"M-{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}"

    draw.text((60, height - 160), "MEMBER NAME", fill=(240, 240, 240), font=font_label)
    draw.text((60, height - 130), member_name, fill=(255, 255, 255), font=font_name)

    draw.text((width - 320, height - 160), "MEMBERSHIP NO.", fill=(240, 240, 240), font=font_label)
    draw.text((width - 320, height - 130), member_id, fill=(255, 255, 255), font=font_name)

    bc_x = width - 320
    bc_y = 60
    for bx in range(bc_x, bc_x + 240, 5):
        if rng.random() > 0.3:
            w_choice = rng.choice([1, 2, 3])
            draw.line([(bx, bc_y), (bx, bc_y + 40)], fill=(255, 255, 255, 200), width=w_choice)

    return card


def generate_blank_card_image(seed: int, width: int = 1000, height: int = 630) -> Image.Image:
    """Generate an unprinted blank plastic PVC card image (CR80 standard).

    Args:
        seed: Deterministic integer seed.
        width: Card width.
        height: Card height.

    Returns:
        Pillow RGBA Image of blank plastic card.
    """
    rng = random.Random(seed)
    colors = [
        ((250, 250, 252), (230, 230, 235)),  # White plastic
        ((215, 220, 225), (185, 190, 198)),  # Silver plastic
        ((235, 215, 160), (195, 175, 120)),  # Gold plastic
        ((25, 30, 45), (15, 20, 30)),        # Matte black plastic
    ]
    color_top, color_bot = rng.choice(colors)

    card = Image.new("RGBA", (width, height), (0, 0, 0, 0))

    radius = 32
    gradient_img = Image.new("RGB", (width, height))
    g_draw = ImageDraw.Draw(gradient_img)
    for y in range(height):
        t = y / max(1, height)
        r = int(color_top[0] * (1 - t) + color_bot[0] * t)
        g = int(color_top[1] * (1 - t) + color_bot[1] * t)
        b = int(color_top[2] * (1 - t) + color_bot[2] * t)
        g_draw.line([(0, y), (width, y)], fill=(r, g, b))

    mask = Image.new("L", (width, height), 0)
    m_draw = ImageDraw.Draw(mask)
    m_draw.rounded_rectangle([0, 0, width, height], radius=radius, fill=255)
    card.paste(gradient_img, (0, 0), mask=mask)

    c_draw = ImageDraw.Draw(card)
    outline_col = (255, 255, 255, 120)
    c_draw.rounded_rectangle(
        [2, 2, width - 2, height - 2], radius=radius, outline=outline_col, width=2
    )

    if rng.random() > 0.5:
        chip_x, chip_y = 120, int(height * 0.42)
        chip_w, chip_h = 130, 100
        gold_fill = (215, 175, 75)
        gold_outline = (160, 125, 45)
        c_draw.rounded_rectangle(
            [chip_x, chip_y, chip_x + chip_w, chip_y + chip_h],
            radius=10,
            fill=gold_fill,
        )
        c_draw.rounded_rectangle(
            [chip_x, chip_y, chip_x + chip_w, chip_y + chip_h],
            radius=10,
            outline=gold_outline,
            width=2,
        )
        c_draw.line(
            [(chip_x + 65, chip_y), (chip_x + 65, chip_y + chip_h)],
            fill=gold_outline,
            width=2,
        )
        c_draw.line(
            [(chip_x, chip_y + 50), (chip_x + chip_w, chip_y + 50)],
            fill=gold_outline,
            width=2,
        )

    return card


def generate_card_rectangle_image(seed: int, width: int = 1600, height: int = 1200) -> Image.Image:
    """Generate non-card rectangular objects (sticky notes, coasters, business cards).

    Args:
        seed: Deterministic integer seed.
        width: Canvas width.
        height: Canvas height.

    Returns:
        Pillow RGB Image.
    """
    rng = random.Random(seed)
    canvas = create_table_background(width, height, rng)
    draw = ImageDraw.Draw(canvas)

    rect_types = ["sticky_note", "plain_coaster", "blank_notepad"]
    rtype = rng.choice(rect_types)

    rw = rng.randint(450, 750)
    rh = int(rw * rng.uniform(0.60, 0.75))
    rx = rng.randint(150, width - rw - 150)
    ry = rng.randint(150, height - rh - 150)

    if rtype == "sticky_note":
        note_colors = [(255, 255, 140), (180, 240, 255), (255, 190, 210)]
        ncol = rng.choice(note_colors)
        draw.rectangle([rx + 8, ry + 8, rx + rw + 8, ry + rh + 8], fill=(60, 45, 30))
        draw.rectangle([rx, ry, rx + rw, ry + rh], fill=ncol)
        for ly in range(ry + 50, ry + rh - 30, 40):
            draw.line([(rx + 30, ly), (rx + rw - 30, ly)], fill=(120, 120, 140), width=1)

    elif rtype == "plain_coaster":
        draw.rounded_rectangle(
            [rx + 10, ry + 10, rx + rw + 10, ry + rh + 10], radius=40, fill=(40, 30, 20)
        )
        draw.rounded_rectangle(
            [rx, ry, rx + rw, ry + rh], radius=40, fill=(210, 185, 150),
            outline=(170, 145, 110), width=3
        )

    else:
        draw.rectangle([rx + 6, ry + 6, rx + rw + 6, ry + rh + 6], fill=(50, 40, 30))
        draw.rectangle(
            [rx, ry, rx + rw, ry + rh], fill=(240, 238, 230),
            outline=(200, 195, 185), width=2
        )
        draw.rectangle([rx, ry, rx + rw, ry + 40], fill=(180, 50, 40))

    return canvas


def generate_screenshot_image(seed: int, width: int = 1600, height: int = 1200) -> Image.Image:
    """Generate a realistic plain mobile screenshot (chat app, notes, or settings).

    Args:
        seed: Deterministic integer seed.
        width: Canvas width.
        height: Canvas height.

    Returns:
        Pillow RGB Image.
    """
    rng = random.Random(seed)
    img = Image.new("RGB", (width, height), (245, 245, 247))
    draw = ImageDraw.Draw(img)

    phone_w = 720
    phone_h = 1100
    phone_x = (width - phone_w) // 2
    phone_y = (height - phone_h) // 2

    draw.rounded_rectangle(
        [phone_x - 12, phone_y - 12, phone_x + phone_w + 12, phone_y + phone_h + 12],
        radius=40,
        fill=(20, 20, 25),
    )
    draw.rounded_rectangle(
        [phone_x, phone_y, phone_x + phone_w, phone_y + phone_h],
        radius=32,
        fill=(255, 255, 255),
    )

    font_bold = get_font(size=18, bold=True)
    font_regular = get_font(size=16, bold=False)

    draw.text((phone_x + 35, phone_y + 15), "09:41", fill=(0, 0, 0), font=font_bold)
    draw.text((phone_x + phone_w - 90, phone_y + 15), "5G  88%", fill=(0, 0, 0), font=font_bold)

    bar_y = phone_y + 45
    draw.rectangle([phone_x, bar_y, phone_x + phone_w, bar_y + 65], fill=(245, 245, 248))
    draw.line(
        [(phone_x, bar_y + 65), (phone_x + phone_w, bar_y + 65)], fill=(220, 220, 225), width=1
    )

    app_mode = rng.choice(["chat", "notes", "settings"])

    if app_mode == "chat":
        draw.text(
            (phone_x + 60, bar_y + 20), "Family Group Chat", fill=(10, 10, 10), font=font_bold
        )
        chat_y = bar_y + 85
        conversations = [
            (False, "Kumusta kayo dyan?"),
            (True, "All good po! On our way home now."),
            (False, "Please buy some pandesal before heading back."),
            (True, "Noted po, passing by the bakery now."),
            (False, "Salamat! Ingat sa biyahe."),
        ]
        for is_me, msg in conversations:
            bbox = draw.textbbox((0, 0), msg, font=font_regular)
            tw = bbox[2] - bbox[0]
            th = bbox[3] - bbox[1]
            bx = phone_x + phone_w - tw - 60 if is_me else phone_x + 40
            bg_col = (0, 122, 255) if is_me else (235, 235, 240)
            txt_col = (255, 255, 255) if is_me else (15, 15, 15)
            draw.rounded_rectangle(
                [bx - 12, chat_y, bx + tw + 12, chat_y + th + 16], radius=16, fill=bg_col
            )
            draw.text((bx, chat_y + 8), msg, fill=txt_col, font=font_regular)
            chat_y += th + 36

    elif app_mode == "notes":
        draw.text(
            (phone_x + 60, bar_y + 20), "Study Notes & Tasks", fill=(10, 10, 10), font=font_bold
        )
        note_y = bar_y + 90
        tasks = [
            "Review IT Capstone Documentation (§9.3, §10.1)",
            "Submit project proposal milestone",
            "Refactor UI components under 150 lines",
            "Prepare unit tests for benchmark suite",
            "Review dataset manifest and statistics",
        ]
        for task in tasks:
            draw.ellipse(
                [phone_x + 45, note_y + 4, phone_x + 60, note_y + 19],
                outline=(150, 150, 160),
                width=2,
            )
            draw.text((phone_x + 75, note_y), task, fill=(30, 30, 30), font=font_regular)
            note_y += 45

    else:  # settings
        draw.text((phone_x + 60, bar_y + 20), "Settings", fill=(10, 10, 10), font=font_bold)
        set_y = bar_y + 85
        settings_items = [
            "Wi-Fi", "Bluetooth", "Display & Brightness",
            "Battery", "Privacy & Security", "About Device",
        ]
        for s_item in settings_items:
            draw.text((phone_x + 45, set_y + 12), s_item, fill=(20, 20, 20), font=font_regular)
            draw.text(
                (phone_x + phone_w - 60, set_y + 12), ">", fill=(160, 160, 170), font=font_bold
            )
            draw.line(
                [(phone_x + 45, set_y + 48), (phone_x + phone_w - 45, set_y + 48)],
                fill=(235, 235, 240),
                width=1,
            )
            set_y += 52

    return img


def generate_negative_sample(
    negative_type: str,
    seed: int,
    photos_dir: str | Path = "data/photos",
) -> tuple[Image.Image, dict[str, Any]]:
    """Generate a single negative sample image and its ground-truth label dictionary.

    Args:
        negative_type: One of ('receipt', 'loyalty_card', 'blank_card',
                       'card_rectangle', 'screenshot', 'photo').
        seed: Deterministic integer seed.
        photos_dir: Directory to look for downloaded public domain photos.

    Returns:
        Tuple of (PIL.Image, ground_truth_dictionary).
    """
    if negative_type not in NEGATIVE_TYPES:
        raise ValueError(
            f"Unknown negative_type '{negative_type}'. Expected one of: {NEGATIVE_TYPES}"
        )

    rng = random.Random(seed)
    extra_meta: dict[str, Any] = {}

    if negative_type == "receipt":
        receipt_crop = generate_receipt_image(seed)
        bg = create_table_background(CANVAS_WIDTH, CANVAS_HEIGHT, rng)
        angle = rng.uniform(-12, 12)
        rotated_receipt = receipt_crop.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
        paste_x = (CANVAS_WIDTH - rotated_receipt.width) // 2 + rng.randint(-50, 50)
        paste_y = (CANVAS_HEIGHT - rotated_receipt.height) // 2 + rng.randint(-40, 40)
        bg.paste(rotated_receipt, (paste_x, paste_y), mask=rotated_receipt)
        img = bg

    elif negative_type == "loyalty_card":
        card_crop = generate_loyalty_card_image(seed)
        bg_type = rng.choice(["table", "hand-held"])
        if bg_type == "table":
            bg = create_table_background(CANVAS_WIDTH, CANVAS_HEIGHT, rng)
        else:
            bg = create_hand_held_background(CANVAS_WIDTH, CANVAS_HEIGHT, rng)
        angle = rng.uniform(-15, 15)
        rotated_card = card_crop.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
        paste_x = (CANVAS_WIDTH - rotated_card.width) // 2 + rng.randint(-40, 40)
        paste_y = (CANVAS_HEIGHT - rotated_card.height) // 2 + rng.randint(-30, 30)
        bg.paste(rotated_card, (paste_x, paste_y), mask=rotated_card)
        img = bg

    elif negative_type == "blank_card":
        blank_crop = generate_blank_card_image(seed)
        bg = create_table_background(CANVAS_WIDTH, CANVAS_HEIGHT, rng)
        angle = rng.uniform(-18, 18)
        rotated_blank = blank_crop.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
        paste_x = (CANVAS_WIDTH - rotated_blank.width) // 2 + rng.randint(-60, 60)
        paste_y = (CANVAS_HEIGHT - rotated_blank.height) // 2 + rng.randint(-40, 40)
        bg.paste(rotated_blank, (paste_x, paste_y), mask=rotated_blank)
        img = bg

    elif negative_type == "card_rectangle":
        img = generate_card_rectangle_image(seed, CANVAS_WIDTH, CANVAS_HEIGHT)

    elif negative_type == "screenshot":
        img = generate_screenshot_image(seed, CANVAS_WIDTH, CANVAS_HEIGHT)

    else:  # photo
        photos_path = Path(photos_dir)
        photo_files = (
            sorted(photos_path.glob("*.jpg")) + sorted(photos_path.glob("*.png"))
            if photos_path.exists()
            else []
        )

        if photo_files:
            chosen_file = photo_files[seed % len(photo_files)]
            loaded = Image.open(chosen_file).convert("RGB")
            img = loaded.resize((CANVAS_WIDTH, CANVAS_HEIGHT), resample=Image.Resampling.BICUBIC)
            match_meta = next(
                (p for p in PUBLIC_DOMAIN_PHOTOS if p["filename"] == chosen_file.name),
                None,
            )
            if match_meta:
                extra_meta = {
                    "photo_source": match_meta["source"],
                    "photo_url": match_meta["url"],
                    "photo_license": match_meta["license"],
                    "photo_license_url": match_meta["license_url"],
                }
            else:
                extra_meta = {
                    "photo_source": chosen_file.name,
                    "photo_license": "Public Domain / CC0",
                }
        else:
            img, proc_meta = generate_procedural_photo(seed, CANVAS_WIDTH, CANVAS_HEIGHT)
            extra_meta = {
                "photo_source": proc_meta["source"],
                "photo_license": proc_meta["license"],
                "photo_license_url": proc_meta["license_url"],
                "is_procedural": True,
            }

    base_name = f"neg_{negative_type}_{seed:04d}"
    label: dict[str, Any] = {
        "class": "negative",
        "document_class": "negative",
        "variant": f"negative-{negative_type}",
        "negative_type": negative_type,
        "is_negative": True,
        "card_corners": [],
        "image_size": [img.width, img.height],
        "fields": [],
        "layout_jitter": False,
        "base_image_id": base_name,
        "condition": None,
        "severity": None,
        **extra_meta,
    }
    return img, label
