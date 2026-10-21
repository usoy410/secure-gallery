"""Fictional field values for synthetic cards (§10.1).

Every value is made up. Names and addresses come from Faker's ``fil_PH`` locale,
ID numbers are random digits in the published format, and payment card numbers
come only from the test numbers that payment processors publish for developers.
"""

from __future__ import annotations

import random

from faker import Faker

# Published developer test card numbers (Visa, Mastercard, Discover, JCB). They
# pass the Luhn check but no issuer assigns them to a real account.
TEST_CARD_NUMBERS: tuple[str, ...] = (
    "4111111111111111",
    "4012888888881881",
    "4242424242424242",
    "4000056655665556",
    "5555555555554444",
    "5105105105105100",
    "5200828282828210",
    "2223003122003222",
    "6011111111111117",
    "3566002020360505",
)

MONTHS = (
    "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
    "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
)  # fmt: skip

# Field types drawn as pictures rather than text. Their label value is None.
IMAGE_FIELDS = frozenset(
    {"face_photo", "qr_code", "signature", "headings", "card_design", "bank_logo",
     "card_network_logo"}
)  # fmt: skip


def luhn_valid(number: str) -> bool:
    """Return True if a digit string passes the Luhn checksum."""
    digits = [int(c) for c in number if c.isdigit()]
    if len(digits) != len(number.replace(" ", "")) or not digits:
        return False
    total = 0
    for i, d in enumerate(reversed(digits)):
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def _digits(rng: random.Random, n: int) -> str:
    return str(rng.randint(1, 9)) + "".join(str(rng.randint(0, 9)) for _ in range(n - 1))


def _person(fake: Faker) -> dict[str, str]:
    return {
        "last": fake.last_name().upper(),
        "first": fake.first_name().upper(),
        "middle": fake.last_name().upper(),
    }


def _birth_date(rng: random.Random) -> tuple[int, int, int]:
    return rng.randint(1950, 2006), rng.randint(1, 12), rng.randint(1, 28)


def _address(fake: Faker) -> str:
    return " ".join(fake.address().upper().split())


def make_values(document_class: str, seed: int) -> dict[str, str]:
    """Return one fictional identity as display strings keyed by field type.

    Multi-line values use "\\n" between lines, matching how the renderer draws them.
    """
    rng = random.Random(seed)
    fake = Faker("fil_PH")
    fake.seed_instance(seed)
    person = _person(fake)
    year, month, day = _birth_date(rng)

    if document_class == "philsys":
        pcn = _digits(rng, 16)
        return {
            "pcn": "-".join(pcn[i : i + 4] for i in range(0, 16, 4)),
            "full_name": f"{person['last']}\n{person['first']}\n{person['middle']}",
            "date_of_birth": f"{MONTHS[month - 1]} {day:02d}, {year}",
            "address": _address(fake),
        }
    if document_class == "lto":
        office = f"{chr(rng.randint(ord('A'), ord('Z')))}{rng.randint(1, 99):02d}"
        return {
            "license_number": f"{office}-{rng.randint(0, 99):02d}-{rng.randint(0, 999999):06d}",
            "name": f"{person['last']}, {person['first']} {person['middle']}",
            "date_of_birth": f"{year}/{month:02d}/{day:02d}",
            "sex": rng.choice(("M", "F")),
            "height": f"{rng.randint(145, 190) / 100:.2f}",
            "weight": str(rng.randint(45, 100)),
            "address": _address(fake),
        }
    if document_class == "payment_card":
        pan = rng.choice(TEST_CARD_NUMBERS)
        return {
            "card_number": " ".join(pan[i : i + 4] for i in range(0, 16, 4)),
            "cardholder_name": f"{person['first']} {person['middle'][0]} {person['last']}",
            "expiry_date": f"{rng.randint(1, 12):02d}/{rng.randint(27, 35):02d}",
            "cvv": f"{rng.randint(0, 999):03d}",
        }
    raise ValueError(f"Unknown document class: {document_class}")
