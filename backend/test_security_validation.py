import os
import uuid
import unittest
from fastapi.testclient import TestClient

from main import app
from database import SessionLocal
from models import EmergencyEvent, CallInvitation


class TestSecurityAndValidation(unittest.TestCase):
    """
    AEGIS Phase 8 Security, Access Control, and Input Validation Audit Test Suite.
    """

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    # --- 1. Secret Isolation & Credential Leak Prevention ---

    def test_fcm_status_never_exposes_private_keys_or_tokens(self):
        """
        Ensure /api/fcm/status never leaks service account private_key,
        client_email, private_key_id, or internal credentials.
        """
        res = self.client.get("/api/fcm/status")
        self.assertEqual(res.status_code, 200)
        content = res.text.lower()
        self.assertNotIn("private_key", content)
        self.assertNotIn("client_email", content)
        self.assertNotIn("private_key_id", content)
        self.assertNotIn("token_uri", content)

    def test_health_endpoints_never_expose_database_credentials(self):
        """
        Ensure /health and /health/database never leak the database connection string,
        password, or host details in response bodies.
        """
        for path in ["/health", "/health/database"]:
            res = self.client.get(path)
            content = res.text.lower()
            self.assertNotIn("password", content)
            self.assertNotIn("postgresql://", content)
            self.assertNotIn("psycopg://", content)

    def test_livekit_token_endpoint_never_exposes_api_secret(self):
        """
        Ensure LiveKit token generation never leaks the server's LIVEKIT_API_SECRET.
        """
        # Create active emergency
        em_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"sec-lk-{uuid.uuid4()}",
            "trigger_source": "manual",
        })
        event_id = em_res.json()["event_id"]

        token_res = self.client.post("/api/calls/token", json={
            "event_id": event_id,
            "device_id": "phone-a",
        })
        self.assertEqual(token_res.status_code, 200)
        token_body = token_res.text
        secret = os.getenv("LIVEKIT_API_SECRET", "aegis_dev_secret_key_for_livekit_safety_calling_32b")
        self.assertNotIn(secret, token_body)

    # --- 2. Input Validation Boundaries ---

    def test_sos_request_rejects_invalid_latitude_longitude(self):
        """
        Test that coordinate ranges are strictly enforced (-90..90 for lat, -180..180 for lng).
        """
        # Latitude out of bounds (> 90)
        res1 = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"sec-bound-{uuid.uuid4()}",
            "latitude": 95.0,
            "longitude": 0.0,
        })
        self.assertEqual(res1.status_code, 422)

        # Latitude out of bounds (< -90)
        res2 = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"sec-bound-{uuid.uuid4()}",
            "latitude": -91.0,
            "longitude": 0.0,
        })
        self.assertEqual(res2.status_code, 422)

        # Longitude out of bounds (> 180)
        res3 = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"sec-bound-{uuid.uuid4()}",
            "latitude": 0.0,
            "longitude": 185.0,
        })
        self.assertEqual(res3.status_code, 422)

    def test_sos_request_rejects_invalid_trigger_source(self):
        """
        Trigger source must match ^(manual|voice)$ pattern.
        """
        res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"sec-trig-{uuid.uuid4()}",
            "trigger_source": "unauthorized_source_hack",
        })
        self.assertEqual(res.status_code, 422)

    def test_path_parameter_length_bounds(self):
        """
        Ensure oversized path parameters (> 64/100 chars) are rejected with 422.
        """
        oversized_id = "a" * 150
        res1 = self.client.get(f"/api/emergencies/{oversized_id}")
        self.assertEqual(res1.status_code, 422)

        res2 = self.client.get(f"/api/calls/{oversized_id}")
        self.assertEqual(res2.status_code, 422)

        res3 = self.client.get(f"/api/invitations/{oversized_id}?device_id=phone-a")
        self.assertEqual(res3.status_code, 422)

    # --- 3. Access Control & Authorization ---

    def test_unauthorized_device_cannot_access_or_manipulate_invitation(self):
        """
        Verify strict authorization:
        1. Eavesdropper cannot view invitation details.
        2. Impersonator cannot answer invitation.
        3. Stranger cannot cancel caller's invitation.
        """
        # Create emergency by phone-a
        em_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"sec-auth-{uuid.uuid4()}",
        })
        event_id = em_res.json()["event_id"]

        # Phone A invites phone-b
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        # Attacker tries to read invitation
        read_res = self.client.get(f"/api/invitations/{inv_id}?device_id=attacker-device")
        self.assertEqual(read_res.status_code, 403)

        # Attacker tries to answer invitation
        ans_res = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "attacker-device",
        })
        self.assertEqual(ans_res.status_code, 403)

        # Attacker tries to cancel caller's invitation
        can_res = self.client.post(f"/api/invitations/{inv_id}/cancel", json={
            "device_id": "attacker-device",
        })
        self.assertEqual(can_res.status_code, 403)

    def test_device_cannot_pair_with_itself(self):
        """
        A device must not be allowed to add itself as its own emergency trusted contact.
        """
        res = self.client.post("/api/contacts/pair", json={
            "owner_device_id": "phone-self",
            "contact_device_id": "phone-self",
        })
        self.assertEqual(res.status_code, 400)
        self.assertIn("cannot pair with itself", res.json()["detail"])

    # --- 4. SQL Injection Immunity ---

    def test_sql_injection_strings_treated_as_literal_identifiers(self):
        """
        Test that SQL injection strings in path and query parameters are safely handled
        via parameterized SQLAlchemy queries without throwing syntax errors.
        """
        sqli_payload = "test' OR '1'='1"
        res = self.client.get(f"/api/emergencies/{sqli_payload}")
        # Expected 404 (not found) rather than 500 (SQL syntax error)
        self.assertEqual(res.status_code, 404)

        res2 = self.client.get(f"/api/contacts/{sqli_payload}")
        self.assertEqual(res2.status_code, 200)
        self.assertEqual(res2.json(), [])

    # --- 5. CORS Headers Verification ---

    def test_cors_headers_present(self):
        """
        Verify that CORS middleware responds properly to preflight and cross-origin requests.
        """
        res = self.client.options(
            "/api/emergencies",
            headers={
                "Origin": "http://localhost:3000",
                "Access-Control-Request-Method": "POST",
            },
        )
        self.assertEqual(res.status_code, 200)
        self.assertIn("access-control-allow-origin", res.headers)


if __name__ == "__main__":
    unittest.main()
