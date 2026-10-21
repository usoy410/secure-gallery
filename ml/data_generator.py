"""Fictional Philippine identity and payment card demographic data generator.

Uses Faker with the fil_PH locale to generate realistic, fictional demographic data,
valid formatted ID numbers, and published test payment card credentials.
"""

from __future__ import annotations

import random
from typing import Any

from faker import Faker

from ml.payment_card import generate_test_card_number

# Fixed bank names for synthetic payment cards
FICTIONAL_BANKS: tuple[str, ...] = (
    "BDO UNIBANK",
    "BANK OF THE PH",
    "METROBANK",
    "UNIONBANK",
    "LANDBANK",
)

FICTIONAL_NETWORKS: tuple[str, ...] = (
    "VISA",
    "MASTERCARD",
)


class SyntheticDataGenerator:
    """Generates fictional cardholder demographics and identifiers for synthetic cards."""

    def __init__(self, seed: int = 42) -> None:
        """Initialize data generator with a specific random seed.

        Args:
            seed: Deterministic integer seed.
        """
        self.seed = seed
        self.rng = random.Random(seed)
        self.faker = Faker("fil_PH")
        self.faker.seed_instance(seed)

    def generate_person(self) -> dict[str, str]:
        """Generate a fictional person profile using Faker fil_PH.

        Returns:
            Dictionary containing names, address, date of birth, and physical attributes.
        """
        first = self.faker.first_name().upper()
        last = self.faker.last_name().upper()
        middle = self.faker.last_name().upper()

        year = self.rng.randint(1965, 2004)
        month = self.rng.randint(1, 12)
        day = self.rng.randint(1, 28)
        dob = f"{year:04d}/{month:02d}/{day:02d}"

        street = self.faker.street_name().upper()
        city = self.faker.city().upper()
        bldg_no = self.rng.randint(10, 999)
        address = f"{bldg_no} {street}, {city}"

        return {
            "first_name": first,
            "last_name": last,
            "middle_name": middle,
            "philsys_full_name": f"{last}, {first}, {middle}",
            "lto_name": f"{last}, {first} {middle}",
            "cardholder_name": f"{first} {last}",
            "date_of_birth": dob,
            "address": address,
            "sex": self.rng.choice(["M", "F"]),
            "height": f"1.{self.rng.randint(52, 85):02d} m",
            "weight": f"{self.rng.randint(48, 85)} kg",
        }

    def generate_fields_for_template(self, template: dict[str, Any]) -> dict[str, str]:
        """Generate field values matching all field types present in the given card template.

        Args:
            template: Loaded template dictionary conforming to templates/schema.json.

        Returns:
            Dictionary mapping field type to generated string value.
        """
        person = self.generate_person()
        template_id = template.get("id", "")
        doc_class = template.get("document_class", "")

        # Generate unique ID numbers
        pcn_parts = [f"{self.rng.randint(1000, 9999)}" for _ in range(4)]
        pcn = "-".join(pcn_parts)

        region_code = self.rng.choice("ABCDEFGHJKLMN")
        lto_license = (
            f"{region_code}{self.rng.randint(1, 99):02d}-"
            f"{self.rng.randint(10, 99):02d}-"
            f"{self.rng.randint(100000, 999999):06d}"
        )

        card_number = generate_test_card_number(self.rng)
        exp_month = self.rng.randint(1, 12)
        exp_year = self.rng.randint(26, 32)
        expiry_date = f"{exp_month:02d}/{exp_year:02d}"
        cvv = f"{self.rng.randint(100, 999):03d}"

        bank = self.rng.choice(FICTIONAL_BANKS)
        network = self.rng.choice(FICTIONAL_NETWORKS)

        # Headings based on document class
        if doc_class == "philsys":
            if "ephilid" in template_id:
                headings = "REPUBLIKA NG PILIPINAS\nEPHILID"
            else:
                headings = "REPUBLIKA NG PILIPINAS\nPAMBANSANG PAGKAKAKILANLAN"
        elif doc_class == "lto":
            headings = (
                "REPUBLIC OF THE PHILIPPINES\n"
                "DEPARTMENT OF TRANSPORTATION\n"
                "LAND TRANSPORTATION OFFICE"
            )
        else:
            headings = "PAYMENT SPECIMEN CARD"

        return {
            "pcn": pcn,
            "pcn_psn": pcn,
            "full_name": person["philsys_full_name"],
            "name": person["lto_name"],
            "cardholder_name": person["cardholder_name"],
            "date_of_birth": person["date_of_birth"],
            "address": person["address"],
            "license_number": lto_license,
            "card_number": card_number,
            "expiry_date": expiry_date,
            "cvv": cvv,
            "sex": person["sex"],
            "height": person["height"],
            "weight": person["weight"],
            "headings": headings,
            "bank_logo": bank,
            "card_network_logo": network,
            "face_photo": "[SYNTHETIC_AVATAR]",
            "signature": "[SPECIMEN_SIGNATURE]",
            "qr_code": f"PHILSYS-CHECK:{pcn}:{person['last_name']}",
        }
