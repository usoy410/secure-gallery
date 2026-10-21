# Synthetic card generator

Renders fictional PhilSys, LTO and payment card images with field-level labels
(SECURE_GALLERY.md §5.3, §10.1). Every template in `templates/` is a variant.

```bash
pip install -e ".[generate]"
python -m ml.generate --variant all --count 100 --seed 0 --out data/synthetic
python -m ml.generate --variant philsys-card --count 10 --seed 7 --out data/synthetic
```

Output: `<out>/<variant>/<variant>-s<seed>-<index>.png` and a `.json` label beside it.
`data/` is gitignored. Commit code only, never generated images.

## What is fictional

- Names and addresses: Faker `fil_PH`.
- PCN, license numbers, dates, CVV: random digits in the printed format.
- Card numbers: only published developer test numbers (`TEST_CARD_NUMBERS`), all Luhn-valid.
- Faces: geometric placeholder avatars. QR codes encode nothing.
- Designs are simplified reconstructions with no security features. Every card has a
  diagonal "SPECIMEN – NOT VALID" watermark.

## Label format (`label_version` 1)

| Key | Meaning |
|---|---|
| `document_class`, `variant` | From the template (`philsys`, `lto`, `payment_card`) |
| `seed` | Per-image seed. Same CLI seed gives byte-identical files. |
| `width`, `height`, `image` | Final image size and file name |
| `background` | `table`, `handheld`, or `screenshot` |
| `card_corners` | Card corners in image pixels: top-left, top-right, bottom-right, bottom-left |
| `fields[]` | `name`, `type`, `sensitive`, `polygon` (image pixels), `value` |

`value` holds the rendered text. Lines are separated by `\n`. Picture fields
(face, QR code, signature, headings, logos) have `value: null`.

Degradation conditions and negatives come in later issues (D6, D7).
