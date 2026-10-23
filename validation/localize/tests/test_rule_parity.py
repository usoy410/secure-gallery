"""Field-rule matching cases shared with the app (issue #17).

The app's FieldRules (android/.../localize/FieldRules.kt) ports _order_lines,
_merge_rows and _match_lines. Both suites check rule_cases.json, so the app finds the
same fields in the same OCR lines as the desktop harness. Needs no Tesseract.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from validation.localize.core import _match_lines, _order_lines, _Word, load_rules

CASES = json.loads((Path(__file__).parent / "rule_cases.json").read_text(encoding="utf-8"))["cases"]
RULES, EXCLUDED = load_rules()


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_rule_case(case: dict) -> None:
    lines = {
        (line["block"], 0, line["line"]): [_Word(w[0], tuple(w[1:])) for w in line["words"]]
        for line in case["lines"]
    }
    found = _match_lines(_order_lines(lines), RULES, EXCLUDED)
    got = [{"field_type": b.field_type, "box": [*b.polygon[0], *b.polygon[2]]} for b in found]
    assert got == case["expected"]
