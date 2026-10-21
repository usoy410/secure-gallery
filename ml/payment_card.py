"""Payment card generation and validation utilities.

Ensures generated synthetic payment card numbers strictly originate from published
industry test ranges (e.g., Stripe, Visa, and Mastercard documentation) and satisfy
the ISO/IEC 7812 Luhn checksum algorithm (Mod 10).
"""

from __future__ import annotations

import random

# Fixed list of complete published test Primary Account Numbers (PANs).
# Why: CLAUDE.md and Issue #5 strictly forbid using real payment card numbers or
# randomly generated digits under test BIN prefixes. All PANs must be complete test card
# numbers published in official developer documentation by major processors and card networks:
#
# Sources:
# - Stripe: https://docs.stripe.com/testing
#   * Visa standard test card: 4242 4242 4242 4242
#   * Visa 3D Secure authentication card: 4000 0000 0000 0002
#   * Visa processing test card: 4000 0000 0000 3220
#   * Visa PIN debit test card: 4000 0000 0000 0341
#   * Mastercard standard test card: 5555 5555 5555 4444
# - Adyen: https://docs.adyen.com/development-resources/testing/test-card-numbers
#   * Visa test card: 4111 1111 1111 1111
#   * JCB test card: 3530 1113 3330 0000
# - Mastercard Developer Documentation:
#   * Mastercard test card: 5105 1051 0510 5100
# - PayPal / Braintree: https://developer.paypal.com/braintree/docs/guides/credit-cards/testing
#   * Mastercard test card: 5454 5454 5454 5454
#   * Discover test card: 6011 1111 1111 1117
# - Discover Developer Documentation:
#   * Discover test card: 6011 0000 0000 0004
PUBLISHED_TEST_PANS: tuple[str, ...] = (
    "4242424242424242",
    "4000000000000002",
    "4000000000003220",
    "4000000000000341",
    "4111111111111111",
    "5555555555554444",
    "5454545454545454",
    "5105105105105100",
    "6011111111111117",
    "6011000000000004",
    "3530111333300000",
)

# BIN prefixes derived from published test PANs for backwards compatibility.
PUBLISHED_TEST_CARD_PREFIXES: tuple[str, ...] = tuple(
    sorted({pan[:6] for pan in PUBLISHED_TEST_PANS})
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
    """Select a 16-digit payment card number from published test PANs passing Luhn check.

    Args:
        rng: Deterministic random instance.

    Returns:
        A space-separated 16-digit card number formatted as 'XXXX XXXX XXXX XXXX'.
    """
    pan = rng.choice(PUBLISHED_TEST_PANS)
    return f"{pan[0:4]} {pan[4:8]} {pan[8:12]} {pan[12:16]}"
