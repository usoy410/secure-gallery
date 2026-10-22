"""Number-structure channel: how many card numbers fit what a redaction left readable (§9.5).

A card number with some digits showing narrows the search: the issuer prefix is
known from the bank and network logos (left visible by design, §8.1), the recovered
digits pin their positions, and the Luhn check digit removes nine in ten of the rest.
If 1,000 or fewer Luhn-valid numbers remain, the field leaks.
"""

from __future__ import annotations

import re
from difflib import SequenceMatcher

ISSUER_PREFIX_LEN = 6  # the BIN: assumed known from the visible bank and network marks
CANDIDATE_LEAK_MAX = 1000
MIN_BLOCK = 2  # recovered digits must match in runs of 2+ to count; single digits can be chance


def recovered_positions(truth: str, recovered: str) -> set[int]:
    """Positions of the true number whose digits appear, in order, in the OCR text."""
    truth_digits = re.sub(r"\D", "", truth)
    seen = re.sub(r"\D", "", recovered)
    matcher = SequenceMatcher(None, truth_digits, seen, autojunk=False)
    return {
        block.a + k
        for block in matcher.get_matching_blocks()
        if block.size >= MIN_BLOCK
        for k in range(block.size)
    }


def luhn_candidates(length: int, known: dict[int, int]) -> int:
    """How many digit strings of ``length`` pass Luhn given the ``known`` position digits."""

    def contribution(position: int, digit: int) -> int:
        if (length - 1 - position) % 2 == 1:  # every second digit from the right is doubled
            digit *= 2
            return digit - 9 if digit > 9 else digit
        return digit

    counts = [0] * 10
    counts[sum(contribution(p, d) for p, d in known.items()) % 10] = 1
    for position in range(length):
        if position in known:
            continue
        nxt = [0] * 10
        for remainder, ways in enumerate(counts):
            if ways:
                for digit in range(10):
                    nxt[(remainder + contribution(position, digit)) % 10] += ways
        counts = nxt
    return counts[0]


def remaining_candidates(truth: str, recovered: str) -> tuple[int, int]:
    """(digits recovered, Luhn-valid numbers left) for a card number and its OCR text."""
    digits = re.sub(r"\D", "", truth)
    found = recovered_positions(truth, recovered)
    known_positions = found | set(range(min(ISSUER_PREFIX_LEN, len(digits))))
    known = {p: int(digits[p]) for p in known_positions}
    return len(found), luhn_candidates(len(digits), known)
