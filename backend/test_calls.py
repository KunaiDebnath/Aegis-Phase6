import os
from datetime import datetime, timezone
import unittest
import uuid
import jwt
from fastapi.testclient import TestClient

from main import app
from database import SessionLocal
from models import EmergencyEvent


class TestLiveKitCallingApi(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def test_missing_livekit_config_returns_503(self):
        # Temporarily unset LiveKit configuration
        old_url = os.environ.get("LIVEKIT_URL")
        old_key = os.environ.get("LIVEKIT_API_KEY")
        old_secret = os.environ.get("LIVEKIT_API_SECRET")

        try:
            if "LIVEKIT_API_SECRET" in os.environ:
                del os.environ["LIVEKIT_API_SECRET"]

            response = self.client.post("/api/calls/token", json={
                "event_id": str(uuid.uuid4()),
                "device_id": "phone-a",
            })
            self.assertEqual(response.status_code, 503)
            data = response.json()
            self.assertIn("not configured", data.get("detail", ""))
            # Ensure no secret or key is mentioned
            self.assertNotIn("aegis_key", str(data))
        finally:
            if old_url is not None:
                os.environ["LIVEKIT_URL"] = old_url
            if old_key is not None:
                os.environ["LIVEKIT_API_KEY"] = old_key
            if old_secret is not None:
                os.environ["LIVEKIT_API_SECRET"] = old_secret

    def test_nonexistent_event_returns_404(self):
        random_event_id = str(uuid.uuid4())
        response = self.client.post("/api/calls/token", json={
            "event_id": random_event_id,
            "device_id": "phone-a",
        })
        self.assertEqual(response.status_code, 404)
        data = response.json()
        self.assertIn("not found or unauthorized", data.get("detail", ""))

    def test_inactive_event_rejected(self):
        # Create an inactive emergency event in the database
        event_id = str(uuid.uuid4())
        with SessionLocal() as db:
            event = EmergencyEvent(
                id=event_id,
                idempotency_key=f"test-inactive-{uuid.uuid4()}",
                device_id="phone-a",
                trigger_source="manual",
                status="resolved",
            )
            db.add(event)
            db.commit()

        response = self.client.post("/api/calls/token", json={
            "event_id": event_id,
            "device_id": "phone-a",
        })
        self.assertEqual(response.status_code, 400)
        self.assertIn("not active", response.json().get("detail", ""))

    def test_authorized_caller_and_responder_receive_valid_tokens(self):
        # 1. Create a legitimate emergency event
        create_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"test-call-auth-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": 37.7749,
            "longitude": -122.4194,
        })
        self.assertEqual(create_res.status_code, 201)
        event_id = create_res.json()["event_id"]

        # 2. Request token for Phone A (Requester)
        res_a = self.client.post("/api/calls/token", json={
            "event_id": event_id,
            "device_id": "phone-a",
            "participant_name": "Phone A (Requester)",
        })
        self.assertEqual(res_a.status_code, 200)
        data_a = res_a.json()

        self.assertIn("token", data_a)
        self.assertEqual(data_a["room_name"], f"emergency-{event_id}")
        self.assertEqual(data_a["identity"], "phone-a")
        self.assertEqual(data_a["expires_in_seconds"], 600)
        self.assertTrue(data_a["server_url"].startswith("ws"))

        # Inspect token JWT claims
        token_a = data_a["token"]
        secret = os.environ.get("LIVEKIT_API_SECRET", "aegis_dev_secret_key_for_livekit_safety_calling_32b")
        claims_a = jwt.decode(token_a, secret, algorithms=["HS256"])
        self.assertEqual(claims_a.get("sub"), "phone-a")
        self.assertEqual(claims_a.get("name"), "Phone A (Requester)")
        video_grants = claims_a.get("video", {})
        self.assertEqual(video_grants.get("room"), f"emergency-{event_id}")
        self.assertTrue(video_grants.get("roomJoin"))
        self.assertTrue(video_grants.get("canPublish"))
        self.assertTrue(video_grants.get("canSubscribe"))

        # 3. Request token for Phone B (Trusted Contact)
        res_b = self.client.post("/api/calls/token", json={
            "event_id": event_id,
            "device_id": "phone-b",
            "participant_name": "Phone B (Trusted Contact)",
        })
        self.assertEqual(res_b.status_code, 200)
        data_b = res_b.json()

        self.assertEqual(data_b["room_name"], f"emergency-{event_id}")
        self.assertEqual(data_b["identity"], "phone-b")
        token_b = data_b["token"]
        claims_b = jwt.decode(token_b, secret, algorithms=["HS256"])
        self.assertEqual(claims_b.get("sub"), "phone-b")
        self.assertEqual(claims_b.get("name"), "Phone B (Trusted Contact)")
        self.assertEqual(claims_b.get("video", {}).get("room"), f"emergency-{event_id}")

        # Ensure tokens for Phone A and Phone B join the SAME room but have DIFFERENT identities
        self.assertEqual(data_a["room_name"], data_b["room_name"])
        self.assertNotEqual(data_a["identity"], data_b["identity"])
        self.assertNotEqual(token_a, token_b)

        # 4. Check /api/calls/{event_id}
        info_res = self.client.get(f"/api/calls/{event_id}")
        self.assertEqual(info_res.status_code, 200)
        info_data = info_res.json()
        self.assertEqual(info_data["room_name"], f"emergency-{event_id}")
        self.assertTrue(info_data["can_call"])

    def test_invalid_uuid_format_rejected(self):
        # Invalid format string
        response = self.client.post("/api/calls/token", json={
            "event_id": "not-a-valid-uuid",
            "device_id": "phone-b",
        })
        self.assertEqual(response.status_code, 422)
        errors = response.json().get("detail", [])
        self.assertTrue(any("event_id must be a valid 36-character canonical UUID" in str(err) for err in errors))

    def test_overlong_repeated_uuid_rejected(self):
        # Repeated copies of UUID (e.g. 72 chars from duplicate paste or append bug)
        base_id = str(uuid.uuid4())
        doubled_id = f"{base_id}{base_id}"
        response = self.client.post("/api/calls/token", json={
            "event_id": doubled_id,
            "device_id": "phone-b",
        })
        self.assertEqual(response.status_code, 422)
        errors = response.json().get("detail", [])
        self.assertTrue(any("event_id must be a valid 36-character canonical UUID" in str(err) for err in errors))

    def test_valid_uuid_accepted_by_schema(self):
        # Canonical UUID should pass schema validation
        valid_id = str(uuid.uuid4()).upper()
        response = self.client.post("/api/calls/token", json={
            "event_id": valid_id,
            "device_id": "phone-b",
        })
        # Should not fail schema validation (422); fails with 404 because random ID doesn't exist in DB
        self.assertEqual(response.status_code, 404)


if __name__ == "__main__":
    unittest.main()
