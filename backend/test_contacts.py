import uuid
import unittest
from fastapi.testclient import TestClient

from main import app
from database import SessionLocal
from models import TrustedContact
from sqlalchemy import select, and_


class TestEmergencyContactApi(unittest.TestCase):
    """
    Test suite for persistent, editable Phone B emergency contact backend API.
    Covers retrieval, creation, updating (no duplicates), phone validation, and authorization.
    """

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def test_01_get_contact_not_configured_returns_404(self):
        """
        When a device has no contact saved, GET /api/contacts/{device_id}/emergency returns 404.
        """
        non_existent_id = f"test-dev-unconfigured-{uuid.uuid4().hex[:8]}"
        res = self.client.get(f"/api/contacts/{non_existent_id}/emergency")
        self.assertEqual(res.status_code, 404)
        self.assertIn("not configured", res.json().get("detail", "").lower())

    def test_02_create_emergency_contact_valid_number(self):
        """
        PUT /api/contacts/{owner_device_id}/emergency creates Phone B contact.
        """
        owner_id = f"test-owner-{uuid.uuid4().hex[:8]}"
        payload = {
            "owner_device_id": owner_id,
            "phone_number": "+1234567890",
            "contact_name": "Phone B Emergency",
        }
        res = self.client.put(f"/api/contacts/{owner_id}/emergency", json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertEqual(data["owner_device_id"], owner_id)
        self.assertEqual(data["contact_device_id"], "phone-b")
        self.assertEqual(data["phone_number"], "+1234567890")
        self.assertTrue(data["is_configured"])
        self.assertIn("updated_at", data)

    def test_03_retrieve_saved_emergency_contact(self):
        """
        GET /api/contacts/{owner_device_id}/emergency retrieves the saved contact with correct fields.
        """
        owner_id = f"test-owner-{uuid.uuid4().hex[:8]}"
        save_res = self.client.put(
            f"/api/contacts/{owner_id}/emergency",
            json={"phone_number": "+919876543210", "contact_name": "Phone B Primary"},
        )
        self.assertEqual(save_res.status_code, 200)

        # Retrieve via primary route
        get_res = self.client.get(f"/api/contacts/{owner_id}/emergency")
        self.assertEqual(get_res.status_code, 200)
        data = get_res.json()
        self.assertEqual(data["owner_device_id"], owner_id)
        self.assertEqual(data["contact_device_id"], "phone-b")
        self.assertEqual(data["phone_number"], "+919876543210")
        self.assertEqual(data["contact_name"], "Phone B Primary")
        self.assertTrue(data["is_configured"])

        # Also retrieve via alias route /api/emergency-contact/{owner_device_id}
        alias_res = self.client.get(f"/api/emergency-contact/{owner_id}")
        self.assertEqual(alias_res.status_code, 200)
        self.assertEqual(alias_res.json()["phone_number"], "+919876543210")

    def test_04_repeated_saves_update_without_creating_duplicates(self):
        """
        Updating the number updates the existing record. The database must contain exactly one
        row for (owner_device_id, 'phone-b').
        """
        owner_id = f"test-owner-{uuid.uuid4().hex[:8]}"

        # Save 1
        res1 = self.client.put(
            f"/api/contacts/{owner_id}/emergency",
            json={"phone_number": "+1111222233", "contact_name": "Initial Name"},
        )
        self.assertEqual(res1.status_code, 200)

        # Save 2 with modified number
        res2 = self.client.put(
            f"/api/contacts/{owner_id}/emergency",
            json={"phone_number": "+4444555566", "contact_name": "Updated Name"},
        )
        self.assertEqual(res2.status_code, 200)
        self.assertEqual(res2.json()["phone_number"], "+4444555566")
        self.assertEqual(res2.json()["contact_name"], "Updated Name")

        # Verify database has strictly 1 record for this owner and phone-b
        with SessionLocal() as db:
            contacts = db.scalars(
                select(TrustedContact).where(
                    and_(
                        TrustedContact.owner_device_id == owner_id,
                        TrustedContact.contact_device_id == "phone-b",
                    )
                )
            ).all()
            self.assertEqual(len(contacts), 1)
            self.assertEqual(contacts[0].contact_phone, "+4444555566")
            self.assertEqual(contacts[0].contact_name, "Updated Name")

    def test_05_invalid_phone_numbers_rejected_with_422(self):
        """
        Validates phone format rules: rejects non-phone strings, letters, too short, too long.
        """
        owner_id = f"test-owner-{uuid.uuid4().hex[:8]}"
        invalid_numbers = [
            "123",              # Too short (< 7 digits)
            "abc1234567",       # Letters
            "12345678901234567",# Too long (> 15 digits)
            "+++---",           # No digits
            "<script>alert(1)</script>", # XSS / invalid string
        ]

        for invalid in invalid_numbers:
            res = self.client.put(
                f"/api/contacts/{owner_id}/emergency",
                json={"phone_number": invalid},
            )
            self.assertEqual(
                res.status_code,
                422,
                f"Expected 422 for invalid number: '{invalid}', got {res.status_code}",
            )

    def test_06_authorization_enforced_between_devices(self):
        """
        Ensure one device cannot read or modify another user's contact.
        """
        owner_id = f"user-a-{uuid.uuid4().hex[:8]}"
        attacker_id = f"user-b-{uuid.uuid4().hex[:8]}"

        # Owner saves contact
        save_res = self.client.put(
            f"/api/contacts/{owner_id}/emergency",
            json={"phone_number": "+1234567890"},
            headers={"X-Device-Id": owner_id},
        )
        self.assertEqual(save_res.status_code, 200)

        # Attacker tries to read owner's contact with header
        read_res = self.client.get(
            f"/api/contacts/{owner_id}/emergency",
            headers={"X-Device-Id": attacker_id},
        )
        self.assertEqual(read_res.status_code, 403)
        self.assertIn("unauthorized", read_res.json()["detail"].lower())

        # Attacker tries to read owner's contact with query param
        read_query_res = self.client.get(
            f"/api/contacts/{owner_id}/emergency?device_id={attacker_id}"
        )
        self.assertEqual(read_query_res.status_code, 403)

        # Attacker tries to modify owner's contact
        mod_res = self.client.put(
            f"/api/contacts/{owner_id}/emergency",
            json={"phone_number": "+9999999999"},
            headers={"X-Device-Id": attacker_id},
        )
        self.assertEqual(mod_res.status_code, 403)

        # Verify owner's contact was untouched
        get_owner = self.client.get(
            f"/api/contacts/{owner_id}/emergency",
            headers={"X-Device-Id": owner_id},
        )
        self.assertEqual(get_owner.status_code, 200)
        self.assertEqual(get_owner.json()["phone_number"], "+1234567890")

    def test_07_device_cannot_pair_with_itself_as_phone_b(self):
        """
        Phone B cannot add itself as its own emergency contact.
        """
        res = self.client.put(
            "/api/contacts/phone-b/emergency",
            json={"phone_number": "+1234567890"},
        )
        self.assertEqual(res.status_code, 400)
        self.assertIn("cannot pair with itself", res.json()["detail"].lower())

    def test_08_no_secrets_or_excess_pii_exposed(self):
        """
        Response bodies must not contain internal database passwords or service credentials.
        """
        owner_id = f"test-owner-{uuid.uuid4().hex[:8]}"
        res = self.client.put(
            f"/api/contacts/{owner_id}/emergency",
            json={"phone_number": "+1987654321"},
        )
        content = res.text.lower()
        self.assertNotIn("password", content)
        self.assertNotIn("secret", content)
        self.assertNotIn("private_key", content)


if __name__ == "__main__":
    unittest.main()
