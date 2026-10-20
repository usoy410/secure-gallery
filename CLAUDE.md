# Secure Gallery

Offline Android app (Kotlin) for team SUDO's NCF BSIT-3B capstone. It scans every gallery image, detects PhilSys IDs, LTO driver's licenses and payment cards, and does three things automatically: it saves a solid-fill redacted copy in the original's album, encrypts the original into a password-protected vault, and re-checks that the redaction worked. Inside the app, an eye toggle switches between the redacted and original image while the vault is unlocked. A review inbox lets users correct automatic redactions.

**Status:** planning. No code yet. Everything is specified in `SECURE_GALLERY.md`. Read only the section you need:

| Need | Section |
|---|---|
| What and why, contribution | §1, §5 |
| Research questions / objectives | §6, §7 |
| Scope, document classes, fields redacted | §8.1, §11 |
| Pipeline and confidence levels | §8.2 |
| Vault, keys, eye toggle | §8.3 |
| Item states, review inbox | §8.4 |
| On-device recheck | §8.5 |
| Algorithms, performance targets (4 GB RAM) | §8.6, §8.7 |
| Tech stack, repo layout | §8.8, §8.9 |
| Metrics, desktop validation harness, usability study | §9 |
| Dataset and ethics | §10 |
| Related systems / literature | §16 / §17–19 |
| Open facts to verify | §20 |

## Hard rules

- **Fully offline:** no `INTERNET` permission in the merged manifest, no cloud calls, all models bundled.
- **Never delete an original** until its vault copy has been decrypted back and hash-checked, and the redacted copy has been saved and re-checked. Deletion goes only through `createDeleteRequest`.
- **Never persist OCR text or field values** in Room, logs, crash reports, or analytics.
- **Never commit real ID or card images.** Test data is synthetic only.
- Kotlin + Jetpack Compose, minSdk 30, one app module, manual DI (`AppContainer`), no Hilt.
- AGPL-3.0, because of Ultralytics YOLO11.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (usoy410/secure-gallery), managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.
