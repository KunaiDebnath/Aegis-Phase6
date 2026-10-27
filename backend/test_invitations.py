import os
import uuid
import time
from datetime import datetime, timedelta, timezone
import unittest
import jwt
from fastapi.testclient import TestClient

from main import app
from database import SessionLocal
from models import EmergencyEvent, CallInvitation, TrustedContact


class TestCallInvitationsApi(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def _create_active_emergency(self, caller: str = "phone-a") -> str:
        res = self.client.post("/api/emergencies", json={
            "device_id": caller,
            "idempotency_key": f"test-inv-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": 37.7749,
            "longitude": -122.4194,
        })
        self.assertEqual(res.status_code, 201)
        return res.json()["event_id"]

    def test_create_invitation_success_and_idempotency(self):
        event_id = self._create_active_emergency("phone-a")

        # 1. Create invitation for authorized trusted contact phone-b
        res1 = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
            "caller_display_name": "Phone A (Requester)",
            "timeout_seconds": 60,
        })
        self.assertEqual(res1.status_code, 201)
        data1 = res1.json()
        inv_id = data1["id"]
        self.assertEqual(data1["emergency_event_id"], event_id)
        self.assertEqual(data1["caller_device_id"], "phone-a")
        self.assertEqual(data1["recipient_device_id"], "phone-b")
        self.assertEqual(data1["status"], "pending")

        # 2. Duplicate create request returns existing pending invitation (idempotent duplicate guard)
        res2 = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
            "caller_display_name": "Phone A (Requester)",
            "timeout_seconds": 60,
        })
        self.assertEqual(res2.status_code, 201)
        data2 = res2.json()
        self.assertEqual(data2["id"], inv_id)
        self.assertIn("already exists", data2["message"])

    def test_unauthorized_caller_cannot_create_invitation(self):
        event_id = self._create_active_emergency("phone-a")

        # Attacker tries to create invitation for phone-a's emergency
        res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "attacker-device",
            "recipient_device_id": "phone-b",
        })
        self.assertEqual(res.status_code, 403)
        self.assertIn("does not own", res.json()["detail"])

    def test_unauthorized_device_cannot_retrieve_invitation(self):
        event_id = self._create_active_emergency("phone-a")
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        # Recipient (phone-b) CAN retrieve
        ok_res = self.client.get(f"/api/invitations/{inv_id}?device_id=phone-b")
        self.assertEqual(ok_res.status_code, 200)

        # Caller (phone-a) CAN retrieve
        ok_caller = self.client.get(f"/api/invitations/{inv_id}?device_id=phone-a")
        self.assertEqual(ok_caller.status_code, 200)

        # Eavesdropper CANNOT retrieve
        bad_res = self.client.get(f"/api/invitations/{inv_id}?device_id=unauthorized-stranger")
        self.assertEqual(bad_res.status_code, 403)

    def test_only_intended_recipient_can_answer(self):
        event_id = self._create_active_emergency("phone-a")
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        # Wrong device tries to answer
        bad_ans = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "random-phone-c",
        })
        self.assertEqual(bad_ans.status_code, 403)
        self.assertIn("not authorized", bad_ans.json()["detail"])

        # Correct recipient answers -> receives short-lived token
        good_ans = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
            "participant_name": "Phone B (Trusted Contact)",
        })
        self.assertEqual(good_ans.status_code, 200)
        ans_data = good_ans.json()
        self.assertEqual(ans_data["status"], "accepted")
        self.assertEqual(ans_data["room_name"], f"emergency-{event_id}")
        self.assertEqual(ans_data["identity"], "phone-b")
        self.assertIn("token", ans_data)

        # Verify token JWT claims
        token = ans_data["token"]
        secret = os.environ.get("LIVEKIT_API_SECRET", "aegis_dev_secret_key_for_livekit_safety_calling_32b")
        claims = jwt.decode(token, secret, algorithms=["HS256"])
        self.assertEqual(claims.get("sub"), "phone-b")
        self.assertEqual(claims.get("video", {}).get("room"), f"emergency-{event_id}")

    def test_duplicate_answer_is_idempotent_and_safe(self):
        event_id = self._create_active_emergency("phone-a")
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        # First answer
        res1 = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
        })
        self.assertEqual(res1.status_code, 200)

        # Second answer (user double-taps Answer)
        res2 = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
        })
        self.assertEqual(res2.status_code, 200)
        self.assertEqual(res2.json()["status"], "accepted")
        self.assertEqual(res2.json()["room_name"], f"emergency-{event_id}")

    def test_expired_invitation_cannot_be_accepted(self):
        event_id = self._create_active_emergency("phone-a")
        # Create invitation with 10 second timeout
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
            "timeout_seconds": 10,
        })
        inv_id = inv_res.json()["id"]

        # Manually backdate expires_at in database to simulate timeout
        with SessionLocal() as db:
            inv = db.get(CallInvitation, inv_id)
            inv.expires_at = datetime.now(timezone.utc) - timedelta(seconds=5)
            db.commit()

        # Try to answer expired invitation
        ans_res = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
        })
        self.assertEqual(ans_res.status_code, 400)
        self.assertIn("expired", ans_res.json()["detail"])

        # Check invitation state in GET
        get_res = self.client.get(f"/api/invitations/{inv_id}?device_id=phone-b")
        self.assertEqual(get_res.status_code, 200)
        self.assertEqual(get_res.json()["status"], "expired")

    def test_dismissed_invitation_workflow(self):
        event_id = self._create_active_emergency("phone-a")
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        # Dismiss invitation
        dismiss_res = self.client.post(f"/api/invitations/{inv_id}/dismiss", json={
            "device_id": "phone-b",
        })
        self.assertEqual(dismiss_res.status_code, 200)
        self.assertEqual(dismiss_res.json()["status"], "dismissed")

        # Crucial requirement: Emergency event must REMAIN ACTIVE after dismiss!
        em_res = self.client.get(f"/api/emergencies/{event_id}")
        self.assertEqual(em_res.status_code, 200)
        self.assertEqual(em_res.json()["status"], "active")

        # Answering a dismissed invitation is rejected
        ans_res = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
        })
        self.assertEqual(ans_res.status_code, 400)
        self.assertIn("dismissed", ans_res.json()["detail"])

    def test_cancelled_invitation_workflow(self):
        event_id = self._create_active_emergency("phone-a")
        inv_res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
        })
        inv_id = inv_res.json()["id"]

        # Caller cancels
        cancel_res = self.client.post(f"/api/invitations/{inv_id}/cancel", json={
            "device_id": "phone-a",
        })
        self.assertEqual(cancel_res.status_code, 200)
        self.assertEqual(cancel_res.json()["status"], "cancelled")

        # Attempting to answer cancelled invitation fails
        ans_res = self.client.post(f"/api/invitations/{inv_id}/answer", json={
            "device_id": "phone-b",
        })
        self.assertEqual(ans_res.status_code, 400)
        self.assertIn("cancelled", ans_res.json()["detail"])

    def test_websocket_signaling_receives_invitation_and_answer(self):
        event_id = self._create_active_emergency("phone-a")

        # Phone B connects to WebSocket signaling
        with self.client.websocket_connect("/api/ws/signaling/phone-b") as ws_b:
            # Phone A connects to WebSocket signaling
            with self.client.websocket_connect("/api/ws/signaling/phone-a") as ws_a:
                # 1. Phone A creates invitation -> Phone B receives WebSocket event
                inv_res = self.client.post("/api/invitations", json={
                    "emergency_event_id": event_id,
                    "caller_device_id": "phone-a",
                    "recipient_device_id": "phone-b",
                    "caller_display_name": "Phone A (Requester)",
                })
                self.assertEqual(inv_res.status_code, 201)
                inv_id = inv_res.json()["id"]

                # Phone B receives incoming_call_invitation
                msg_b = ws_b.receive_json()
                self.assertEqual(msg_b["type"], "incoming_call_invitation")
                self.assertEqual(msg_b["invitation_id"], inv_id)
                self.assertEqual(msg_b["emergency_event_id"], event_id)
                self.assertEqual(msg_b["caller_device_id"], "phone-a")

                # 2. Phone B answers invitation -> Phone A receives call_invitation_answered
                ans_res = self.client.post(f"/api/invitations/{inv_id}/answer", json={
                    "device_id": "phone-b",
                    "participant_name": "Phone B",
                })
                self.assertEqual(ans_res.status_code, 200)

                # Phone A receives notification that Phone B answered
                msg_a = ws_a.receive_json()
                self.assertEqual(msg_a["type"], "call_invitation_answered")
                self.assertEqual(msg_a["invitation_id"], inv_id)
                self.assertEqual(msg_a["recipient_device_id"], "phone-b")

    def test_contact_pairing_endpoint(self):
        res = self.client.post("/api/contacts/pair", json={
            "owner_device_id": "device-user1",
            "contact_device_id": "device-trusted1",
            "contact_name": "Emergency Ally",
            "contact_phone": "+1987654321",
        })
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["owner_device_id"], "device-user1")
        self.assertEqual(res.json()["contact_device_id"], "device-trusted1")

        # List contacts
        list_res = self.client.get("/api/contacts/device-user1")
        self.assertEqual(list_res.status_code, 200)
        contacts = list_res.json()
        self.assertTrue(any(c["contact_device_id"] == "device-trusted1" for c in contacts))

    def test_fcm_status_endpoint(self):
        res = self.client.get("/api/fcm/status")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("configured", data)
        self.assertIn("initialized", data)
        self.assertIsInstance(data["configured"], bool)
        self.assertIsInstance(data["initialized"], bool)
        # Verify no credentials or private keys leaked in response
        self.assertNotIn("private_key", str(data))
        self.assertNotIn("client_email", str(data))

    def test_fcm_token_registration_and_dry_run_test(self):
        # 1. Register device FCM token
        fake_token = "fcm_test_token_dummy_1234567890_abcdefghijklmnopqrstuvwxyz"
        reg_res = self.client.post("/api/devices/fcm-token", json={
            "device_id": "phone-fcm-test",
            "fcm_token": fake_token,
        })
        self.assertEqual(reg_res.status_code, 200)
        self.assertEqual(reg_res.json()["status"], "registered")

        # 2. Test fcm test endpoint with missing token
        bad_res = self.client.post("/api/fcm/test", json={
            "device_id": "nonexistent-device-id",
        })
        self.assertEqual(bad_res.status_code, 400)

        # 3. Test fcm test endpoint with dry_run against registered device
        # If Firebase Admin is initialized with real credentials, dry_run verifies connectivity with Google FCM servers.
        # If the dummy token is rejected by Google FCM servers as invalid format, it returns 502 with detail.
        status_res = self.client.get("/api/fcm/status")
        if status_res.json().get("initialized"):
            test_res = self.client.post("/api/fcm/test", json={
                "device_id": "phone-fcm-test",
                "dry_run": True,
            })
            # Google servers will return 502 with invalid token error for dummy token, which proves communication with Google FCM servers
            self.assertIn(test_res.status_code, [200, 502])
            if test_res.status_code == 502:
                self.assertIn("FCM delivery failed", test_res.json()["detail"])
        else:
            # If not configured, should return 502 with not initialized
            test_res = self.client.post("/api/fcm/test", json={
                "device_id": "phone-fcm-test",
                "dry_run": True,
            })
            self.assertEqual(test_res.status_code, 502)

    def test_call_invitation_dispatches_fcm_and_reports_status(self):
        # 1. Register FCM token for recipient
        self.client.post("/api/devices/fcm-token", json={
            "device_id": "phone-b",
            "fcm_token": "fcm_dummy_token_for_recipient_1234567890",
        })

        event_id = self._create_active_emergency("phone-a")
        res = self.client.post("/api/invitations", json={
            "emergency_event_id": event_id,
            "caller_device_id": "phone-a",
            "recipient_device_id": "phone-b",
            "caller_display_name": "Phone A (Requester)",
        })
        self.assertEqual(res.status_code, 201)
        data = res.json()
        self.assertIn("fcm_status", data)
        # With a dummy token, fcm_status indicates failure or dispatched (depending on dry_run / Google server response)
        self.assertTrue(len(data["fcm_status"]) > 0)
