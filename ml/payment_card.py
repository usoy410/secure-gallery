"""Payment card generation and validation utilities.

Ensures generated synthetic payment card numbers strictly originate from published
industry test ranges (e.g., Stripe, Visa, and Mastercard documentation) and satisfy
the ISO/IEC 7812 Luhn checksum algorithm (Mod 10).
"""

from __future__ import annotations

import random

# Published industry test Primary Account Number (PAN) prefixes.
# Why: CLAUDE.md and Issue #5 strictly forbid using real payment card numbers.
# We restrict all generated PANs to well-known test bins published by major payment processors:
# - Stripe Visa test range: 424242
# - Visa documentation test range: 400000, 411111
# - Stripe Mastercard test range: 555555
# - Mastercard documentation test range: 510510
PUBLISHED_TEST_CARD_PREFIXES: tuple[str, ...] = (
    "424242",
    "400000",
    "411111",
    "555555",
    "510510",
)


def calculate_luhn_check_digit(prefix: str) -> int:
    """Compute the single-digit Luhn checksum (ISO/IEC 7812) for a numeric prefix.

    For a target 16-digit card number, the final digit (index 15) is the check digit.
    When reading from right to left, odd positions (index 14, 12, 10, ...) are doubled.

    Args:
        prefix: 15-digit string of numeric characters.

    Returns:
        Integer check digit in [0, 9] that will make the total Luhn sum a multiple of 10.
    """
    digits = [int(char) for char in prefix if char.isdigit()]
    total = 0
    # From right to left of the 15-digit prefix:
    for i, digit in enumerate(reversed(digits)):
        if i % 2 == 0:  # Represents odd positions in the final 16-digit number
            doubled = digit * 2
            total += doubled - 9 if doubled > 9 else doubled
        else:
            total += digit

    return (10 - (total % 10)) % 10


def is_luhn_valid(card_number: str) -> bool:
    """Verify whether a payment card number satisfies the Luhn algorithm.

    Args:
        card_number: String containing the card number (spaces and hyphens ignored).

    Returns:
        True if the number passes Luhn check, False otherwise.
    """
    digits = [int(char) for char in card_number if char.isdigit()]
    if not digits:
        return False

    total = 0
    for i, digit in enumerate(reversed(digits)):
        if i % 2 == 1:
            doubled = digit * 2
            total += doubled - 9 if doubled > 9 else doubled
        else:
            total += digit

    return total % 10 == 0


def generate_test_card_number(rng: random.Random) -> str:
    """Generate a 16-digit payment card number from published test ranges passing Luhn check.

    Args:
        rng: Deterministic random instance.

    Returns:
        A space-separated 16-digit card number formatted as 'XXXX XXXX XXXX XXXX'.
    """
    prefix = rng.choice(PUBLISHED_TEST_CARD_PREFIXES)
    # Remaining random digits before check digit (prefix + middle = 15 digits total)
    middle_len = 15 - len(prefix)
    middle_digits = "".join(rng.choice("0123456789") for _ in range(middle_len))
    prefix_15 = prefix + middle_digits
    check_digit = calculate_luhn_check_digit(prefix_15)
    full_digits = prefix_15 + str(check_digit)

    # Format into standard 4-digit groups
    return f"{full_digits[0:4]} {full_digits[4:8]} {full_digits[8:12]} {full_digits[12:16]}"
