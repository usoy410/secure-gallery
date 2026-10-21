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
| photo_01 | `desk_workspace.jpg` | CC0 1.0 Universal | Wikimedia Commons (Desk workspace) |
| photo_02 | `coffee_cup.jpg` | CC0 1.0 Universal | Wikimedia Commons (A small cup of coffee) |
| photo_03 | `street_scene.jpg` | Public Domain | Wikimedia Commons (Seattle street) |
| photo_04 | `landscape_nature.jpg` | CC0 1.0 Universal | Wikimedia Commons (Fuji cherry blossom) |
| photo_05 | `bookshelves.jpg` | Public Domain | Wikimedia Commons (Bibliotheque Assemblée Nationale) |

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
  Diagonal `SPECIMEN – NOT VALID` watermarks are burnt into clean card renders.
- **Are ground-truth labels provided?**
  Yes, every image is paired with a pixel-accurate JSON label and indexed in `manifest.csv`.

## 5. Uses

- **What tasks can this dataset be used for?**
  - Document detection and bounding polygon localization.
  - OCR field extraction accuracy benchmarking.
  - Redaction leak testing and safeguard audit (evaluating residual detectability).
  - Evaluating false-positive rates on receipts, loyalty cards, and ordinary photos.
- **What tasks should this dataset NOT be used for?**
  This dataset must NOT be used to generate forged or counterfeit identification documents.
  All demographic data is fictional.

## 6. Distribution and Maintenance

- **How is the dataset distributed?**
  Exported via `python -m ml.generate dataset --config <config.yaml>`.
- **License:** AGPL-3.0 for generator code and synthetic outputs;
  CC0 / Public Domain for referenced background photos.
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
