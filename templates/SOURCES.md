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
  - Official PhilSys Portal Security Features & Specimen:  
    `https://philsys.gov.ph/features-of-philid/`
  - Philippine Information Agency (PIA) PhilID Security Guidelines:  
    `https://pia.gov.ph/features/2021/05/20/know-your-philid-security-features`
  - Bangko Sentral ng Pilipinas (BSP) PhilSys Production Announcement:  
    `https://www.bsp.gov.ph/`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Standard CR80 form factor (aspect ratio ~1.5858).
  - Front layout features top banner with national header and PhilSys emblem; primary portrait photo on left; PhilSys Card Number (PCN), Last Name, Given Names, Middle Name, Date of Birth, and permanent address arranged across the center-right; ghost portrait and signature line in the lower center-right.
  - Per PSA privacy-by-design guidelines, the physical card displays the 16-digit PCN on the front; the confidential 12-digit PSN is microprinted on the reverse.

---

## 2. ePhilID (Printed Paper Version)

- **Template File:** `templates/philsys-ephilid.json`
- **Variant ID:** `philsys-ephilid`
- **Document Class:** `philsys` (PhilSys ID per §8.1)
- **Issuing Authority:** Philippine Statistics Authority (PSA)
- **Public Specimen URLs:**
  - Official PSA Printable ePhilID Announcement:  
    `https://philsys.gov.ph/ephilid/`
  - PSA Advisory on the Implementation and Acceptance of the ePhilID:  
    `https://psa.gov.ph/content/psa-launches-printable-ephilid`
  - Bureau of Customs / PSA Joint Advisory on ePhilID Specimen:  
    `https://customs.gov.ph/wp-content/uploads/2022/10/PSA-Advisory-ePhilID.pdf`
  - Official Verification Portal (PhilSys Check):  
    `https://verify.philsys.gov.ph`
- **Access Date:** 2026-10-20
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
  - Official LTO Licensing Guidelines and Permitted Card Specifications:  
    `https://lto.gov.ph/license-permit-issuance/`
  - LTO Official Announcement on Pearlized White Driver's License Rollout:  
    `https://lto.gov.ph/`
  - DOTr Press Release on Polycarbonate Driver's License with Enhanced Security Features:  
    `https://dotr.gov.ph/`
- **Access Date:** 2026-10-20
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
  - DOTr & LTO 2017 5-Year Validity Driver's License Specimen Presentation:  
    `https://dotr.gov.ph/55-dotr-news/353-lto-rolls-out-driver-s-licenses-with-5-year-validity.html`
  - Philippine News Agency (PNA) LTO 5-Year License Card Specimen Launch:  
    `https://www.pna.gov.ph/articles/1007827`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Standard CR80 form factor (aspect ratio ~1.5858) with light-blue guilloche background theme.
  - Layout matches current card field positioning (photo and signature on left, license number and name at upper right, biometrics mid-right, address lower-right) with earlier restriction code formatting.

---

## 5. Generic Payment Card (Front)

- **Template File:** `templates/payment-card-front.json`
- **Variant ID:** `payment-card-front`
- **Document Class:** `payment_card` (Payment card per §8.1)
- **Standardization Bodies:** International Organization for Standardization (ISO), EMVCo, Payment Card Brands (Visa / Mastercard)
- **Public Specifications & Guidelines:**
  - ISO/IEC 7810:2019 Identification cards — Physical characteristics (ID-1 dimensions):  
    `https://www.iso.org/standard/70483.html`
  - ISO/IEC 7813:2006 Information technology — Identification cards — Financial transaction cards:  
    `https://www.iso.org/standard/39736.html`
  - Visa Card Brand Mark and Card Design Guidelines:  
    `https://usa.visa.com/dam/VCOM/download/about-visa/visa-card-design-standards.pdf`
  - Mastercard Brand Mark Guidelines:  
    `https://brand.mastercard.com/brandcenter/mastercard-brand-mark/guidelines.html`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Dimensions: 85.60 mm × 53.98 mm (aspect ratio ~1.5858).
  - Per §8.2 notes: payment card layouts vary by bank. Template covers the standard ISO ID-1 number / expiry band and the cardholder name area; the rest relies on OCR and Luhn checks. Bank logo (upper-left) and card network brand mark (lower-right) remain left visible per §8.1.

---

## 6. Generic Payment Card (Back)

- **Template File:** `templates/payment-card-back.json`
- **Variant ID:** `payment-card-back`
- **Document Class:** `payment_card` (Payment card per §8.1)
- **Standardization Bodies:** ISO/IEC 7811 / ISO/IEC 7813 / PCI Security Standards Council
- **Public Specifications & Guidelines:**
  - ISO/IEC 7811 Identification cards — Recording technique (Magnetic stripe):  
    `https://www.iso.org/standard/70484.html`
  - Payment Card Industry (PCI) Security Standards Council - Card Security Features:  
    `https://www.pcisecuritystandards.org/`
- **Access Date:** 2026-10-20
- **Layout Notes:**
  - Dimensions: 85.60 mm × 53.98 mm (aspect ratio ~1.5858).
  - Features magnetic stripe along top band; signature panel in mid-vertical section with 3-digit or 4-digit CVV / CVC code positioned on or adjacent to the panel; bank support / contact branding and network hologram in lower section left visible.
