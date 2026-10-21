"""Datasheet generator for Secure Gallery synthetic benchmark dataset.

Implements the standard dataset documentation framework (Gebru et al.,
'Datasheets for Datasets', CACM 2021) documenting:
- Motivation & Purpose
- Composition & Distribution (counts per class, split, condition, and failed redaction)
- Collection & Synthesis Process
- Data Isolation & Preprocessing
- Uses & Safeguards
- Known Gaps & Limitations
- Public Domain Photo Attribution & Licensing
"""

from __future__ import annotations

from typing import Any

from ml.fetch_photos import PUBLIC_DOMAIN_PHOTOS


def build_datasheet_markdown(
    records: list[dict[str, Any]],
    dataset_name: str = "Secure Gallery Benchmark Dataset",
    seed: int = 42,
) -> str:
    """Generate Markdown formatted Datasheet for Datasets from manifest records.

    Args:
        records: List of manifest record dictionaries.
        dataset_name: Display name for the dataset.
        seed: Random seed used during generation.

    Returns:
        Full markdown string adhering to Gebru et al. specification.
    """
    total_samples = len(records)

    # Aggregate counts by split
    split_counts: dict[str, int] = {}
    for r in records:
        s = r.get("split", "unknown")
        split_counts[s] = split_counts.get(s, 0) + 1

    # Aggregate counts by class
    class_counts: dict[str, int] = {}
    for r in records:
        c = r.get("class", r.get("document_class", "unknown"))
        class_counts[c] = class_counts.get(c, 0) + 1

    # Aggregate counts by condition
    cond_counts: dict[str, int] = {}
    for r in records:
        cond = r.get("condition") or "clean_baseline"
        cond_counts[cond] = cond_counts.get(cond, 0) + 1

    # Aggregate counts by negative type
    neg_counts: dict[str, int] = {}
    for r in records:
        if r.get("is_negative"):
            nt = r.get("negative_type", "other")
            neg_counts[nt] = neg_counts.get(nt, 0) + 1

    # Aggregate counts by redaction type
    redact_counts: dict[str, int] = {}
    for r in records:
        rt = r.get("redaction_type")
        if rt and rt != "none":
            redact_counts[rt] = redact_counts.get(rt, 0) + 1

    # Table for split breakdown
    split_rows = "\n".join(
        f"| `{s}` | {cnt} | {cnt / max(1, total_samples) * 100:.1f}% |"
        for s, cnt in sorted(split_counts.items())
    )

    # Table for class breakdown
    class_rows = "\n".join(
        f"| `{c}` | {cnt} | {cnt / max(1, total_samples) * 100:.1f}% |"
        for c, cnt in sorted(class_counts.items())
    )

    # Table for condition breakdown
    cond_rows = "\n".join(
        f"| `{cond}` | {cnt} |" for cond, cnt in sorted(cond_counts.items())
    )

    # Table for negative breakdown
    neg_rows = "\n".join(
        f"| `{nt}` | {cnt} |" for nt, cnt in sorted(neg_counts.items())
    ) if neg_counts else "| _None_ | 0 |"

    # Table for failed redaction breakdown with 3rd column description / expected leak
    redaction_descriptions: dict[str, str] = {
        "complete": "Solid coverage (leak: none)",
        "partial": "Box truncated, partial field exposed (leak: partial_text_exposed)",
        "misplaced": "Box offset from field (leak: offset_uncovered_field)",
        "ineffective": "Transparent/blur fill (leak: transparent_or_blur_fill_legible)",
    }
    redact_rows = "\n".join(
        f"| `{rt}` | {cnt} | {redaction_descriptions.get(rt, 'Simulated redaction defect')} |"
        for rt, cnt in sorted(redact_counts.items())
    ) if redact_counts else "| _None_ | 0 | None |"

    # Public domain photos table
    photo_rows = "\n".join(
        f"| {p['id']} | [{p['filename']}]({p['url']}) | {p['license']} | {p['description']} |"
        for p in PUBLIC_DOMAIN_PHOTOS
    )

    return f"""# Datasheet for {dataset_name}

Following Gebru et al. (*Datasheets for Datasets*, Communications of the ACM, 2021).

## 1. Motivation

- **For what purpose was the dataset created?**
  This dataset was created for the Secure Gallery capstone project (NCF BSIT-3B)
  to train and benchmark offline on-device document detectors and redaction safeguard
  algorithms (SECURE_GALLERY.md §9.3, §10.1). Specifically, it benchmarks:
  1. Localization of Philippine IDs and payment cards.
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
- **How many instances are there in total?**
  Total samples: **{total_samples}** (generated with base seed `{seed}`).

### Counts by Split
Split partitioning is strictly identity-based: all condition variants and redactions
of a base identity land in the same split (guaranteeing zero data leakage).

| Split | Count | Proportion |
|---|---|---|
{split_rows}

### Counts by Document Class

| Class | Count | Proportion |
|---|---|---|
{class_rows}

### Counts by Image Condition

| Condition | Count |
|---|---|
{cond_rows}

### Negative Sample Composition

| Negative Type | Count |
|---|---|
{neg_rows}

### Failed Redaction Cases (Test Split)

| Redaction Type | Count | Description / Expected Leak |
|---|---|---|
{redact_rows}

### Public Domain / CC0 Photo Sources

| ID | Image / URL | License | Description |
|---|---|---|---|
{photo_rows}

## 3. Collection Process

- **How was the data associated with each instance acquired?**
  - **Cards:** Procedurally generated demographic fields using Faker `fil_PH`, valid Luhn PANs,
    SVG/vector template layout rendering, and homography compositing.
  - **Negatives:** Procedurally synthesized receipts (Philippine supermarkets), loyalty cards,
    blank plastic cards, card-shaped rectangles, chat screenshots, and curated CC0 photos.
  - **Redactions:** Algorithmic redaction masks applied to test split cards covering partial,
    misplaced, or ineffective (semi-transparent/blur) areas.
- **Does the dataset contain all possible instances or is it a sample?**
  Parameterized procedural sample from the generator space.

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
"""
