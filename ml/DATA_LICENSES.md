# Data Licenses and Provenance Audit

This document records the provenance, licensing terms, and usage constraints for external and synthetic datasets used in Secure Gallery, conforming to §10.1, §10.2, §19.1, and §20.1 item 4 of `SECURE_GALLERY.md`.

## 1. Summary of Rules

1. **No External Data in Evaluation Splits**: Candidate datasets from public repositories (such as Roboflow) are strictly restricted to the **training split** only (`train`). They are **never** admitted to `val` or `test` splits to prevent test-set pollution and maintain reproducible benchmark evaluation.
2. **License Recording Prerequisite**: No external dataset may be ingested or converted by `ml.converter` unless its exact license and provenance are formally recorded in this registry.
3. **No Real PII Committed**: Real identity documents, personal data, and unredacted images must never be checked into git. All committed sample fixtures and benchmark baselines must be 100% synthetic.

---

## 2. Dataset Registry

### 2.1 Synthetic Benchmark Dataset (D7)
- **Source**: Secure Gallery procedural generator (`ml.generate dataset`)
- **License**: AGPL-3.0 (Repository license)
- **PII Status**: 100% fictional / synthetic (Faker `fil_PH` locale, synthesized avatars, procedural card backgrounds)
- **Allowed Splits**: `train`, `val`, `test`
- **Verification Status**: Verified. Generated purely in memory with deterministic seeds.

### 2.2 Roboflow Universe: Philippine IDs (`test-usmly/philippine-ids-kmiv4`)
- **Dataset ID / URI**: `test-usmly/philippine-ids-kmiv4` (https://universe.roboflow.com/test-usmly/philippine-ids-kmiv4)
- **License**: CC BY 4.0 (Creative Commons Attribution 4.0 International)
- **Provenance / Author**: Roboflow Universe user `test-usmly`
- **Target Classes**: Philippine ID cards (PhilSys, UMID, Driver's License)
- **Usage Scope**: Optional detector training only (`split: train`). Strictly prohibited from `val` and `test`.
- **License Compliance Notes**: Attribution must be maintained in training documentation. Commercial and academic fine-tuning permitted under CC BY 4.0.
- **Verification Status**: Audited candidate for training-split augmentation.

### 2.3 Roboflow Universe: Philippine IDs (`walramworkspace/philippine-ids-mfzrh`)
- **Dataset ID / URI**: `walramworkspace/philippine-ids-mfzrh` (https://universe.roboflow.com/walramworkspace/philippine-ids-mfzrh)
- **License**: CC BY 4.0 (Creative Commons Attribution 4.0 International)
- **Provenance / Author**: Roboflow Universe user `walramworkspace`
- **Target Classes**: Philippine identification cards
- **Usage Scope**: Optional detector training only (`split: train`). Strictly prohibited from `val` and `test`.
- **License Compliance Notes**: Attribution must be maintained in training documentation.
- **Verification Status**: Audited candidate for training-split augmentation.

---

## 3. Converter Validation Protocol

The dataset converter (`ml.converter`) verifies external dataset sources against this registry:
- If an external directory is supplied (e.g. `--roboflow-dir`), the converter checks that the dataset's identifier matches an audited entry in this file.
- If verified, all samples from the external source are forcibly mapped to `split: train`.
- If unverified or missing from this document, conversion aborts with an explicit license error.
