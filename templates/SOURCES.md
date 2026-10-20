# Template Specimen Sources and Measurement Log

This document records the publicly published specimen images, official advisories, and technical standards used to establish normalized coordinate boundaries and field definitions for each card template in `templates/`.

In accordance with `CLAUDE.md` and project ethics rules, **no real ID or payment card images are stored or committed to the repository**. Only measured normalized coordinates derived from public government and regulatory specimens are retained.

---

## 1. PhilSys National ID (Polycarbonate Card)

- **Template File:** `templates/philsys-card.json`
- **Variant ID:** `philsys-card`
- **Document Class:** `philsys` (PhilSys ID per §8.1)
- **Issuing Authority:** Philippine Statistics Authority (PSA) / Bangko Sentral ng Pilipinas (BSP)
- **Governing Law / Standard:** Republic Act No. 11055 (Philippine Identification System Act), ISO/IEC 7810 ID-1 standard (85.60 mm × 53.98 mm)
- **Public Specimen URLs:**
  - Wikimedia Commons Public Specimen Image (Sample PhilSys ID PSA Front):  
    `https://commons.wikimedia.org/wiki/File:Sample_PhilSys_ID_PSA.jpg`
  - Wikimedia Commons Public Specimen Card Layout (PhilSys Card Sample):  
    `https://commons.wikimedia.org/wiki/File:Philippine_Identification_System_(PhilSys)_card_sample.png`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Standard CR80 form factor (aspect ratio ~1.5858).
  - Front layout features top banner with national header and PhilSys emblem; primary portrait photo on left; PhilSys Card Number (PCN), Last Name, Given Names, Middle Name, Date of Birth, and permanent address arranged across the center; ghost portrait, signature line, and QR code in the mid/lower-right.
  - Per §8.1, all demographic information (PCN, name, DOB, address, photo, signature, QR code) are designated sensitive for redaction, while headings and national emblems remain left visible.

---

## 2. ePhilID (Printed Paper Version)

- **Template File:** `templates/philsys-ephilid.json`
- **Variant ID:** `philsys-ephilid`
- **Document Class:** `philsys` (PhilSys ID per §8.1)
- **Issuing Authority:** Philippine Statistics Authority (PSA)
- **Public Specimen URLs:**
  - Official PhilSys Check Digital Verification Specification & Live Validator:  
    `https://verify.philsys.gov.ph`
- **Access Date:** 2026-10-20
- **Source Availability Note:**
  - Fewer standalone direct public specimen URLs remain accessible for ePhilID via automated HTTP requests due to government portal anti-scraping WAF protections (which return HTTP 403 to automated clients) and archived press advisories. Measurements for the paper variant were established directly using the PhilSys Check verification standard specifications (`verify.philsys.gov.ph`) combined with standard PhilSys CR80 print-fold cut dimensions.
- **Layout Notes:**
  - Single-surface card-sized cut/fold section on standard paper printout (aspect ratio ~1.5858).
  - Features top header banner; photo, PCN, Full Name, Date of Birth, and residential address on the left/center; large cryptographic QR code on the right designed for verification via PhilSys Check; digital signature / validation block below QR code.

---

## 3. LTO Driver's License (Current Pearlized White Polycarbonate Card)

- **Template File:** `templates/lto-dl-current.json`
- **Variant ID:** `lto-dl-current`
- **Document Class:** `lto` (LTO driver's license per §8.1)
- **Issuing Authority:** Land Transportation Office (LTO), Department of Transportation (DOTr)
- **Public Specimen URLs:**
  - Wikimedia Commons Official Specimen (Front of a Philippine Driver's License LTO sample, published in LTO Driver's Manual Vol. 1, 2nd Edition):  
    `https://commons.wikimedia.org/wiki/File:Front_of_a_Philippine_Driver%E2%80%99s_License_(LTO)_sample.jpg`
- **Access Date:** 2026-10-20
- **Source Availability Note:**
  - Bare agency homepages (lto.gov.ph, dotr.gov.ph) have been dropped in favor of the specific public specimen published from the official LTO Driver's Manual.
- **Layout Notes:**
  - Standard CR80 form factor (85.60 mm × 53.98 mm, aspect ratio ~1.5858).
  - Front layout: Republic of the Philippines / LTO header at top; primary color photograph on left with specimen signature immediately below; license number prominently at top-right; name block, birthdate, sex, height, and weight in mid section; complete residential address across bottom-right.
  - Per §8.1, sex, height, weight, and headings remain left visible while license number, name, birthdate, address, photo, and signature are redacted.

---

## 4. LTO Driver's License (Previous Blue Polycarbonate Card)

- **Template File:** `templates/lto-dl-previous.json`
- **Variant ID:** `lto-dl-previous`
- **Document Class:** `lto` (LTO driver's license per §8.1)
- **Issuing Authority:** Land Transportation Office (LTO), Department of Transportation (DOTr)
- **Public Specimen URLs:**
  - Wikimedia Commons Public Specimen (Philippine Driver's License Front 5-Year Design):  
    `https://commons.wikimedia.org/wiki/File:Philippine_driver%27s_license_front.png`
- **Access Date:** 2026-10-20
- **Source Availability Note:**
  - 2017-era agency press release links (e.g. earlier DOTr news releases and PNA article 1007827) are retired or return HTTP 404/403. Specimen measurements were preserved from the verified public Wikimedia Commons repository entry.
- **Layout Notes:**
  - Standard CR80 form factor (aspect ratio ~1.5858) with light-blue guilloche background theme.
  - Layout matches current card field positioning (photo and signature on left, license number and name at upper right, biometrics mid-right, address lower-right) with earlier restriction code formatting.

---

## 5. Generic Payment Card (Front)

- **Template File:** `templates/payment-card-front.json`
- **Variant ID:** `payment-card-front`
- **Document Class:** `payment_card` (Payment card per §8.1)
- **Standardization Bodies:** International Organization for Standardization (ISO), EMVCo, Payment Card Brands (Visa / Mastercard)
- **Public Specimen URLs:**
  - Wikimedia Commons Standard Credit/Debit Card Front Technical Specimen Diagram:  
    `https://commons.wikimedia.org/wiki/File:CCardFront.svg`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Dimensions: 85.60 mm × 53.98 mm (aspect ratio ~1.5858).
  - Per §8.2 notes: payment card layouts vary by bank. Template covers the standard ISO ID-1 number / expiry band and the cardholder name area; the rest relies on OCR and Luhn checks. Bank logo (upper-left) and card network brand mark (lower-right) remain left visible per §8.1.
  - Exempt from CVV redaction, which is situated on the reverse side of the card (`payment-card-back`).

---

## 6. Generic Payment Card (Back)

- **Template File:** `templates/payment-card-back.json`
- **Variant ID:** `payment-card-back`
- **Document Class:** `payment_card` (Payment card per §8.1)
- **Standardization Bodies:** ISO/IEC 7811 / ISO/IEC 7813 / PCI Security Standards Council
- **Public Specimen URLs:**
  - Wikimedia Commons Standard Credit/Debit Card Reverse Technical Specimen Diagram:  
    `https://commons.wikimedia.org/wiki/File:CCardBack.svg`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Dimensions: 85.60 mm × 53.98 mm (aspect ratio ~1.5858).
  - Features magnetic stripe along top band; signature panel in mid-vertical section with 3-digit or 4-digit CVV / CVC code positioned on or adjacent to the panel; bank support / contact branding and network hologram in lower section left visible.
  - Exempt from primary account number (PAN), cardholder name, and expiry date redactions, which are situated on the front side (`payment-card-front`).
