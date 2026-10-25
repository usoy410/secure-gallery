# Datasheet for Secure Gallery Synthetic Benchmark Dataset

Following Gebru et al. (*Datasheets for Datasets*, Communications of the ACM, 2021).

## 1. Motivation

- **For what purpose was the dataset created?**
  This dataset was created for the Secure Gallery capstone project (NCF BSIT-3B)
  to train and benchmark offline on-device document detectors and redaction safeguard
  algorithms (SECURE_GALLERY.md §9.3, §10.1). Specifically, it benchmarks:
  1. Localization of Philippine IDs (PhilSys Card, ePhilID, LTO Driver's Licenses) and payment cards.
  2. False positive resistance against common non-ID negatives.
  3. Recheck safeguard detection of defective or incomplete redactions.
- **Who created the dataset?**
  Procedurally synthesized by the Secure Gallery synthetic benchmark pipeline (`ml.generate`).
- **Who funded the creation of the dataset?**
  NCF BSIT-3B capstone team SUDO. Fictional data only; no commercial funding.

## 2. Composition

- **What do the instances that comprise the dataset represent?**
  Synthetic images of Philippine identification cards, payment cards, negative non-ID items,
  and test images with simulated redaction defects.
- **Dataset instances and formats:**
  - Standard resolution: 1600x1200 RGB PNG images without non-deterministic metadata chunks.
  - Paired ground-truth JSON files with pixel-accurate card corners, field bounding polygons,
    fictional text values, degradation condition metadata, and redaction defect labels.
  - Complete index cataloged in `manifest.csv`.

### Splits Strategy (Zero Data Leakage)
Split partitioning is strictly identity-based: all condition variants and redactions
of a base identity land in the same split (train, val, test are mutually exclusive).
Default proportions: 70% train, 15% val, 15% test.

### Document Classes
1. `philsys`: Philippine National ID (`philsys-card`, `philsys-ephilid`).
2. `lto_dl`: LTO Driver's License (`lto-dl-current`, `lto-dl-previous`).
3. `payment_card`: Payment Cards (`payment-card-front`, `payment-card-back`).
4. `negative`: Non-ID negative samples (`receipt`, `loyalty_card`, `blank_card`, `card_rectangle`, `screenshot`, `photo`).

### Negative Sample Types
1. `receipt`: Synthetic supermarket and convenience store receipts (thermal receipt typography, line items, VAT, totals).
2. `loyalty_card`: Retail and cafe customer membership cards (ID-1 form factor without ID data).
3. `blank_card`: Unprinted plastic PVC cards (CR80 standard) with bevel/shadow.
4. `card_rectangle`: Card-shaped rectangular geometries (sticky notes, coasters, business cards).
5. `screenshot`: Mobile phone screenshots (chat conversation, notes, settings screen).
6. `photo`: Everyday scene photos from CC0 public-domain sources or procedural fallback.

### Failed Redaction Cases (Test Split)
1. `complete`: Ground-truth 100% solid opaque black box covering target fields (expected leak: `none`).
2. `partial`: Redaction box covering only 30%–70% of field area (expected leak: `partial_text_exposed`).
3. `misplaced`: Offset box shifted away from intended field (expected leak: `offset_uncovered_field`).
4. `ineffective`: Semi-transparent fill or mild blur (expected leak: `transparent_or_blur_fill_legible`).

### Public Domain / CC0 Photo Sources

| ID | Filename | License | Source / Attribution |
|---|---|---|---|
| photo_01 | `fuji_apple.jpg` | Public domain | Scott Bauer, USDA ARS (File:Fuji apple.jpg) |
| photo_02 | `yellowstone_river.jpg` | Public domain | Ed Austin/Herb Jones, National Park Service (File:Yellowstone River in Hayden Valley.jpg) |
| photo_03 | `brick_wall_texture.jpg` | Public domain | Georges Grondin (File:Brick wall texture.jpg) |
| photo_04 | `desk_met_12029.jpg` | CC0 1.0 Universal | The Metropolitan Museum of Art (File:Desk MET 12029.jpg) |
| photo_05 | `desk_and_bookcase.jpg` | CC0 1.0 Universal | The Metropolitan Museum of Art (File:Desk and Bookcase MET DT171.jpg) |

## 3. Collection Process

- **How was the data associated with each instance acquired?**
  - **Cards:** Procedurally generated demographic fields using Faker `fil_PH`, valid Luhn PANs,
    SVG/vector template layout rendering, and homography compositing.
  - **Negatives:** Procedurally synthesized receipts (Philippine supermarkets), loyalty cards,
    blank plastic cards, card-shaped rectangles, chat screenshots, and curated CC0 photos.
  - **Redactions:** Algorithmic redaction masks applied to test split cards covering partial,
    misplaced, or ineffective (semi-transparent/blur) areas.

## 4. Preprocessing, Cleaning, and Labeling

- **Was any preprocessing or filtering performed?**
  Images are rendered deterministically to 1600x1200 RGB canvas without non-deterministic chunks.
  Every card carries a diagonal `SPECIMEN – NOT VALID` watermark burnt into its pixels: red
  on light cards, light grey on dark ones. The renderer measures it (`watermark_contrast` in
  each label, the mean change on its own pixels, 0 to 255) and a release fails below 20.
- **Are ground-truth labels provided?**
  Yes, every image is paired with a pixel-accurate JSON label and indexed in `manifest.csv`.

## 5. Uses

- **What tasks can this dataset be used for?**
  - Document detection and bounding polygon localization.
  - OCR field extraction accuracy benchmarking.
  - Redaction leak testing and safeguard audit (evaluating residual detectability).
  - Evaluating false-positive rates on receipts, loyalty cards, and ordinary photos.
- **What tasks should this dataset NOT be used for?**
  - **Identity verification or KYC.** No image shows a real person or a real document, so a
    model or rule tuned on it says nothing about whether a real ID is genuine.
  - **Making, printing or passing off fake IDs or payment cards**, with or without the
    watermark. All names, numbers, addresses and faces are fictional; the card numbers pass
    the Luhn check only so redaction rules can be tested.
  - **Training face recognition.** The faces are procedural cartoons.
  - Any use that removes or hides the `SPECIMEN – NOT VALID` watermark.

## 6. Distribution and Maintenance

- **How is the dataset distributed?**
  Exported via `python -m ml.generate dataset --config <config.yaml>`.
- **License:** the data (images, labels, manifest, datasheet) is CC BY 4.0. The generator
  code is AGPL-3.0, a separate license that does not cover the data. Background photos are
  public domain or CC0, and the bundled Liberation Sans font is SIL OFL 1.1. A release bundle
  lists every third-party input in `LICENSE-DATA.md`.
- **Reproducibility:** `python -m ml.generate release --seed <S>` rebuilds a release, and
  `--verify <bundle>` checks every file against its `SHA256SUMS`.
- **Maintenance:** Maintained by the Secure Gallery core development team.

## 7. Known Gaps and Limitations

1. **Synthetic Sim-to-Real Gap:** While physical degradation conditions (blur, glare, rotation,
   perspective, low resolution, compression, crop, occlusion) are modeled, synthetic renders
   may not reproduce all microscopic print defects, plastic delamination, or specialized
   hologram diffraction patterns found on worn physical IDs.
2. **Camera Sensor Diversity:** Real mobile cameras produce varying Bayer sensor noise, rolling
   shutter distortion, and lens chromatic aberrations that are approximated.
3. **Roboflow Separation:** In accordance with SECURE_GALLERY.md §10.1, Roboflow PH ID data is
   strictly separated and never enters the benchmark test set.
4. **Template accuracy:** the card layouts were drawn from public specimen images. Until
   each is checked against a real card (#4, recorded in `templates/SOURCES.md`), field
   positions may be off by a few percent of the card width.
5. **Watermark only on cards:** negatives carry no watermark. A detector could learn the
   watermark as a cue for "card"; compare results on cards with the watermark area masked
   before relying on false-positive rates.
6. **Card backs:** only the payment card has a back; PhilSys and LTO backs are not modelled.
7. **Language and lighting:** text is English and Filipino in one font family, and lighting
   comes from the condition generator, not real scenes.
