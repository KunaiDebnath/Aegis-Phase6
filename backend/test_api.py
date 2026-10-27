from datetime import datetime, timezone
import json
import unittest
import uuid
from fastapi.testclient import TestClient
from main import app

BASE_URL = "http://127.0.0.1:8000"


class TestAegisBackendApi(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def make_request(self, path, method="GET", data=None):
        if method == "GET":
            res = self.client.get(path)
        elif method == "POST":
            res = self.client.post(path, json=data)
        elif method == "PUT":
            res = self.client.put(path, json=data)
        elif method == "DELETE":
            res = self.client.delete(path)
        else:
            raise ValueError(f"Unsupported method {method}")
        try:
            return res.status_code, res.json()
        except Exception:
            return res.status_code, {"raw": res.text}

    def test_health_check(self):
        status, data = self.make_request("/health")
        self.assertEqual(status, 200)
        self.assertEqual(data.get("status"), "healthy")

    def test_database_health(self):
        status, data = self.make_request("/health/database")
        self.assertEqual(status, 200)
        self.assertEqual(data.get("database"), "connected")
        self.assertEqual(data.get("status"), "healthy")

    def test_emergency_creation_without_location_backward_compatible(self):
        idempotency_key = f"test-no-loc-{uuid.uuid4()}"
        payload = {
            "device_id": "phone-a",
            "idempotency_key": idempotency_key,
            "trigger_source": "manual",
        }

        status, data = self.make_request("/api/emergencies", method="POST", data=payload)
        self.assertEqual(status, 201)
        self.assertEqual(data.get("status"), "active")
        self.assertEqual(data.get("message"), "Emergency event created")
        self.assertEqual(data.get("trigger_source"), "manual")
        self.assertIsNone(data.get("latitude"))
        self.assertIsNone(data.get("longitude"))

        # Duplicate submission with same key
        status2, data2 = self.make_request("/api/emergencies", method="POST", data=payload)
        self.assertEqual(status2, 201)
        self.assertEqual(data2.get("message"), "Emergency event already exists")
        self.assertEqual(data2.get("event_id"), data.get("event_id"))

    def test_emergency_creation_with_valid_location(self):
        idempotency_key = f"test-loc-{uuid.uuid4()}"
        now_iso = datetime.now(timezone.utc).isoformat()
        payload = {
            "device_id": "phone-a",
            "idempotency_key": idempotency_key,
            "trigger_source": "manual",
            "latitude": 37.774929,
            "longitude": -122.419416,
            "location_accuracy_meters": 12.5,
            "location_timestamp": now_iso,
        }

        # 1. Create with location
        status, data = self.make_request("/api/emergencies", method="POST", data=payload)
        self.assertEqual(status, 201)
        self.assertEqual(data.get("status"), "active")
        self.assertEqual(data.get("message"), "Emergency event created")
        self.assertAlmostEqual(data.get("latitude"), 37.774929, places=5)
        self.assertAlmostEqual(data.get("longitude"), -122.419416, places=5)
        self.assertAlmostEqual(data.get("location_accuracy_meters"), 12.5, places=1)
        event_id = data.get("event_id")

        # 2. Duplicate submission with location retains same event
        status2, data2 = self.make_request("/api/emergencies", method="POST", data=payload)
        self.assertEqual(status2, 201)
        self.assertEqual(data2.get("message"), "Emergency event already exists")
        self.assertEqual(data2.get("event_id"), event_id)
        self.assertAlmostEqual(data2.get("latitude"), 37.774929, places=5)

        # 3. Retrieve event and check location fields
        status3, data3 = self.make_request(f"/api/emergencies/{event_id}")
        self.assertEqual(status3, 200)
        self.assertEqual(data3.get("event_id"), event_id)
        self.assertAlmostEqual(data3.get("latitude"), 37.774929, places=5)
        self.assertAlmostEqual(data3.get("longitude"), -122.419416, places=5)
        self.assertAlmostEqual(data3.get("location_accuracy_meters"), 12.5, places=1)
        self.assertIsNotNone(data3.get("location_timestamp"))

    def test_voice_trigger_source_and_retrieval(self):
        idempotency_key = f"test-voice-{uuid.uuid4()}"
        payload = {
            "device_id": "phone-a",
            "idempotency_key": idempotency_key,
            "trigger_source": "voice",
            "latitude": 12.971598,
            "longitude": 77.594562,
            "location_accuracy_meters": 5.0,
        }

        status, data = self.make_request("/api/emergencies", method="POST", data=payload)
        self.assertEqual(status, 201)
        self.assertEqual(data.get("trigger_source"), "voice")
        event_id = data.get("event_id")

        # Retrieve and verify stored trigger source
        status2, data2 = self.make_request(f"/api/emergencies/{event_id}")
        self.assertEqual(status2, 200)
        self.assertEqual(data2.get("trigger_source"), "voice")

    def test_invalid_trigger_source_rejected(self):
        status, data = self.make_request("/api/emergencies", method="POST", data={
            "device_id": "phone-a",
            "idempotency_key": f"test-invalid-trigger-{uuid.uuid4()}",
            "trigger_source": "automatic",
        })
        self.assertEqual(status, 422)

    def test_invalid_coordinates_rejected(self):
        # Invalid latitude (> 90)
        status1, data1 = self.make_request("/api/emergencies", method="POST", data={
            "device_id": "phone-a",
            "idempotency_key": f"test-invalid-lat-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": 95.0,
            "longitude": 10.0,
        })
        self.assertEqual(status1, 422)

        # Invalid latitude (< -90)
        status2, data2 = self.make_request("/api/emergencies", method="POST", data={
            "device_id": "phone-a",
            "idempotency_key": f"test-invalid-lat-neg-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": -91.5,
            "longitude": 10.0,
        })
        self.assertEqual(status2, 422)

        # Invalid longitude (> 180)
        status3, data3 = self.make_request("/api/emergencies", method="POST", data={
            "device_id": "phone-a",
            "idempotency_key": f"test-invalid-lng-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": 40.0,
            "longitude": 181.0,
        })
        self.assertEqual(status3, 422)

        # Invalid longitude (< -180)
        status4, data4 = self.make_request("/api/emergencies", method="POST", data={
            "device_id": "phone-a",
            "idempotency_key": f"test-invalid-lng-neg-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": 40.0,
            "longitude": -185.0,
        })
        self.assertEqual(status4, 422)

        # Invalid accuracy (< 0)
        status5, data5 = self.make_request("/api/emergencies", method="POST", data={
            "device_id": "phone-a",
            "idempotency_key": f"test-invalid-acc-{uuid.uuid4()}",
            "trigger_source": "manual",
            "latitude": 40.0,
            "longitude": 50.0,
            "location_accuracy_meters": -5.0,
        })
        self.assertEqual(status5, 422)


if __name__ == "__main__":
    unittest.main()
