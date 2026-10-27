import os
import uuid
from datetime import datetime, timedelta, timezone
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy.exc import IntegrityError, OperationalError

from sqlalchemy.orm import Session
from main import app
from database import SessionLocal
from models import EmergencyEvent, CallInvitation, DeviceRegistration


class TestFailureHandlingAndIntegration(unittest.TestCase):
    """
    AEGIS Phase 7 Integration and Failure Handling Test Suite.
    Validates failure isolation, duplicate idempotency, database recovery,
    and resilience against partial subsystem outages.
    """

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def test_emergency_creation_concurrent_integrity_error_handled(self):
        """
        Simulate a concurrent database collision where another worker inserted
        the same idempotency_key at the exact same millisecond.
        Verifies that IntegrityError is caught and the existing record is returned.
        """
        idempotency_key = f"race-test-{uuid.uuid4()}"
        payload = {
            "device_id": "phone-a",
            "idempotency_key": idempotency_key,
            "trigger_source": "manual",
            "latitude": 37.7749,
            "longitude": -122.4194,
        }

        # 1. First create the initial event
        res1 = self.client.post("/api/emergencies", json=payload)
        self.assertEqual(res1.status_code, 201)
        original_event_id = res1.json()["event_id"]

        # 2. Simulate a race condition: db.commit raises IntegrityError
        with patch.object(Session, "commit", side_effect=IntegrityError("duplicate key", params=None, orig=Exception())):
            res2 = self.client.post("/api/emergencies", json=payload)
            self.assertEqual(res2.status_code, 201)
            self.assertEqual(res2.json()["event_id"], original_event_id)
            self.assertEqual(res2.json()["message"], "Emergency event already exists")

    def test_invitation_creation_when_call_already_accepted_is_idempotent(self):
        """
        If Phone B has already accepted a call invitation, repeated invitation requests
        for the same event and recipient must NOT create a duplicate room or invitation,
        and must safely return the existing accepted invitation.
        """
        # Create active emergency
        em_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"inv-dup-test-{uuid.uuid4()}",
            "trigger_source": "manual",
        })
        event_id = em_res.json()["event_id"]

        # Phone A creates initial invitation
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
            "timeout_seconds": 60,
        })
        self.assertEqual(inv_res.status_code, 201)
        inv_id = inv_res.json()["id"]

        # Phone B accepts the invitation
        ans_res = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
        })
        self.assertEqual(ans_res.status_code, 200)
        self.assertEqual(ans_res.json()["status"], "accepted")

        # Phone A tries to create invitation again while call is already active
        dup_inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
            "timeout_seconds": 60,
        })
        self.assertEqual(dup_inv_res.status_code, 201)
        data = dup_inv_res.json()
        self.assertEqual(data["id"], inv_id)
        self.assertEqual(data["status"], "accepted")
        self.assertIn("already accepted", data["message"])

    def test_database_health_failure_returns_503(self):
        """
        Verify that database disconnections or operational errors return HTTP 503
        without unhandled internal 500 server crashes.
        """
        with patch("main.check_database_connection", side_effect=OperationalError("connection refused", params=None, orig=Exception())):
            res = self.client.get("/health/database")
            self.assertEqual(res.status_code, 503)
            self.assertIn("Database connection failed", res.json()["detail"])

    def test_subsystem_isolation_emergency_event_persists_across_call_lifecycle(self):
        """
        Rule 6: Ensure failure or dismissal of LiveKit calling does NOT disable
        or mutate the underlying emergency event.
        """
        em_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"persist-test-{uuid.uuid4()}",
            "trigger_source": "voice",
            "latitude": 12.9716,
            "longitude": 77.5946,
        })
        event_id = em_res.json()["event_id"]

        # 1. Dismissal does not affect emergency event
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        self.client.post(f"/api/invitations/{inv_id}/dismiss", json={"device_id": "phone-b"})

        em_check1 = self.client.get(f"/api/emergencies/{event_id}")
        self.assertEqual(em_check1.status_code, 200)
        self.assertEqual(em_check1.json()["status"], "active")
        self.assertEqual(em_check1.json()["trigger_source"], "voice")

        # 2. Re-invitation after dismissal works and emergency remains active
        inv_res2 = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        self.assertEqual(inv_res2.status_code, 201)
        inv_id2 = inv_res2.json()["id"]
        self.assertNotEqual(inv_id, inv_id2)

        # 3. Expiry does not affect emergency event
        with SessionLocal() as db:
            inv = db.get(CallInvitation, inv_id2)
            inv.expires_at = datetime.now(timezone.utc) - timedelta(seconds=10)
            db.commit()

        # Check invitation is expired
        inv_check = self.client.get(f"/api/invitations/{inv_id2}?device_id=phone-b")
        self.assertEqual(inv_check.status_code, 200)
        self.assertEqual(inv_check.json()["status"], "expired")

        # Emergency event must STILL be active
        em_check2 = self.client.get(f"/api/emergencies/{event_id}")
        self.assertEqual(em_check2.status_code, 200)
        self.assertEqual(em_check2.json()["status"], "active")

    def test_fcm_failure_does_not_block_invitation_creation(self):
        """
        Rule 6: FCM delivery failures must not prevent invitation creation or WebSocket dispatch.
        """
        em_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"fcm-fail-test-{uuid.uuid4()}",
            "trigger_source": "manual",
        })
        event_id = em_res.json()["event_id"]

        # Register a simulated broken FCM token
        self.client.post("/api/devices/fcm-token", json={
            "device_id": "phone-b",
            "fcm_token": "broken_fcm_token_xyz_99999",
        })

        # Mock send_fcm_call_invitation to simulate network or credential failure
        with patch("main.send_fcm_call_invitation", return_value=(False, "Simulated FCM timeout")):
            inv_res = self.client.post("/api/invitations", json={
                "emergency_event_id": event_id,
                "caller_device_id": "phone-a",
                "recipient_device_id": "phone-b",
            })
            # Creation succeeds despite FCM failure
            self.assertEqual(inv_res.status_code, 201)
            data = inv_res.json()
            self.assertEqual(data["status"], "pending")
            self.assertIn("failed: Simulated FCM timeout", data["fcm_status"])

    def test_livekit_unconfigured_error_isolation(self):
        """
        Verify that if LiveKit is not configured, requesting a call token returns 503
        with descriptive detail without crashing the application.
        """
        em_res = self.client.post("/api/emergencies", json={
            "device_id": "phone-a",
            "idempotency_key": f"livekit-fail-test-{uuid.uuid4()}",
            "trigger_source": "manual",
        })
        event_id = em_res.json()["event_id"]

        with patch.dict(os.environ, {"LIVEKIT_URL": ""}):
            res = self.client.post("/api/calls/token", json={
                "event_id": event_id,
                "device_id": "phone-a",
            })
            self.assertEqual(res.status_code, 503)
            self.assertIn("not configured", res.json()["detail"])


if __name__ == "__main__":
    unittest.main()
