# AEGIS Phase 6: Incoming Emergency Call Notifications and Answer Flow

## 1. Overview and Architecture

AEGIS Phase 6 implements a real-time, bidirectional incoming emergency call notification and answer flow between Phone A (the person requesting assistance) and Phone B (the trusted contact responder).

In Phase 5, Phone B had to copy the Event ID from an emergency SMS and manually join the call. Phase 6 introduces real-time signaling, incoming call notifications on Phone B with Answer and Dismiss action buttons, automatic call connection, server-validated invitation lifecycle (expiry, dismissal, cancellation), and mutual exclusion audio coordination.

### Architecture Diagram

```
+-----------------------------------------------------------------------------------------+
| Host Machine (LAN: 192.168.1.4)                                                         |
|                                                                                         |
|   +------------------------------------+    +-----------------------------------------+ |
|   | Self-Hosted LiveKit Server         |    | FastAPI Backend                         | |
|   | Signaling: 7880 / TCP              |    | HTTP API: Port 8000                     | |
|   | ICE TCP: 7881 / TCP                |    | WebSocket Signaling: /api/ws/signaling  | |
|   | ICE UDP: 50000-50100               |    | Endpoints: /api/invitations/*           | |
|   +-----------------+------------------+    +--------------------+--------------------+ |
|                     ^                                            |                      |
|                     | WebRTC Media                               | Invitation Lifecycle |
|                     |                                            v                      |
|                     |                       +--------------------+--------------------+ |
|                     |                       | PostgreSQL Database                     | |
|                     |                       | Tables: emergency_events,               | |
|                     |                       |         call_invitations,               | |
|                     |                       |         trusted_contacts,               | |
|                     |                       |         device_registrations            | |
|                     |                       +-----------------------------------------+ |
+---------------------|--------------------------------------------|----------------------+
                      |                                            |
        +-------------+-------------+                +-------------+-------------+
        |                           |                |                           |
        v                           v                v                           v
+-----------------------+   +-----------------------+       WebSocket Signaling
| Phone A (Requester)   |   | Phone B (Responder)   |       & Real-time Call Push
| Emergency Alert Trigger|  | Incoming Call Notif   |
| LiveKit Requester     |   | Answer / Dismiss Flow |
+-----------------------+   +-----------------------+
```

---

## 2. Signaling Architecture & Workflow

### 1. Triggering Emergency (Phone A)
1. Phone A initiates emergency via Manual SOS button or hands-free Voice Recognition (`"I need help"`).
2. `EmergencyOrchestrator` acquires GPS fix, sends idempotent event request to `/api/emergencies`, and dispatches cellular SIM SMS alert to Phone B.
3. Once the emergency event ID is recorded, Phone A immediately enters the LiveKit room `emergency-{event_id}`.
4. Concurrently, Phone A calls `POST /api/invitations` to dispatch a formal call invitation to Phone B (`recipient_device_id = "phone-b"`).

### 2. Delivering Call Invitation to Phone B
1. The FastAPI backend validates that the emergency event is active, confirms caller ownership, and verifies the trusted contact relationship.
2. The server creates an unpredictable UUID `CallInvitation` record in PostgreSQL with a 60-second server-side expiry (`expires_at`).
3. If an active pending invitation already exists for this event and recipient, the backend idempotently returns the existing invitation without creating duplicate rooms.
4. The backend immediately dispatches a real-time WebSocket signaling payload to Phone B:
   ```json
   {
     "type": "incoming_call_invitation",
     "invitation_id": "...",
     "emergency_event_id": "...",
     "caller_device_id": "phone-a",
     "caller_display_name": "Phone A (Requester)",
     "created_at": "...",
     "expires_at": "...",
     "status": "pending"
   }
   ```
5. If Firebase Cloud Messaging is configured, an FCM high-priority data message is also dispatched to Phone B's registered FCM token.

### 3. Notification Presentation on Phone B
1. Phone B's `CallSignalingClient` (or `AegisFirebaseMessagingService`) receives the payload.
2. `EmergencyCallNotificationManager` creates a dedicated high-priority notification channel:
   - Channel: `aegis_incoming_call_channel` ("AEGIS Emergency Incoming Calls")
   - Priority: `PRIORITY_MAX` with ringtone and vibration pattern
   - Action Button 1: **ANSWER** (triggers `CallActionReceiver`)
   - Action Button 2: **DISMISS** (triggers `CallActionReceiver`)
   - Lock screen privacy: Private mode prevents sensitive emergency details from displaying on the lock screen.
3. If the AEGIS app is open, an **INCOMING EMERGENCY CALL** card is displayed at the top of the UI with an active countdown timer (60s).

### 4. Answering Call (Phone B)
1. Tapping **ANSWER** on the notification or in-app dialog invokes `POST /api/invitations/{id}/answer`.
2. The backend verifies that `device_id` matches the authorized recipient, confirms the invitation is not expired or cancelled, and marks the invitation status `accepted`.
3. The backend issues a short-lived (10-minute) signed LiveKit WebRTC access token.
4. LiveKit room connection starts on Phone B via `LiveKitCallManager.startCallWithAuthorizedToken`.
5. The backend sends a WebSocket event `call_invitation_answered` to Phone A.
6. Phone A updates its calling badge to `"Connected with: Phone B (Trusted Contact)"`.
7. Two-way Opus audio flows between Phone A and Phone B over WebRTC.

### 5. Dismissing Call (Phone B)
1. Tapping **DISMISS** calls `POST /api/invitations/{id}/dismiss`.
2. The invitation status transitions to `dismissed`.
3. The incoming notification is cancelled immediately.
4. The backend sends a `call_invitation_dismissed` event to Phone A.
5. **Critical Safety Guarantee**: The underlying emergency event **remains active in PostgreSQL**; it is NOT marked resolved, and emergency SMS/GPS records remain intact.

### 6. Timeout and Stale Invitations
1. Invitations expire automatically after 60 seconds if unheeded.
2. The backend enforces server-side expiry: any attempt to answer after `expires_at` is rejected with `HTTP 400 ("Call invitation has expired")`.
3. Phone B's countdown timer cancels stale notifications.

---

## 3. Database Schema and Alembic Migrations

Applied via Alembic migration `9f5e54e80073_add_call_invitations_and_contacts`:

### `call_invitations` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | String(36) | Primary Key | UUID token identifying the invitation |
| `emergency_event_id` | String(36) | Foreign Key -> `emergency_events.id` (CASCADE) | Associated emergency event |
| `caller_device_id` | String(100) | Indexed | Calling device ("phone-a") |
| `recipient_device_id` | String(100) | Indexed | Target trusted contact ("phone-b") |
| `status` | String(20) | Indexed | `pending`, `accepted`, `dismissed`, `expired`, `cancelled` |
| `caller_display_name`| String(100) | Nullable | Human-readable caller label |
| `created_at` | DateTime(UTC) | Not Null | Creation timestamp |
| `expires_at` | DateTime(UTC) | Indexed | Server-enforced expiry timestamp |
| `responded_at` | DateTime(UTC) | Nullable | Answer/dismiss timestamp |

### `trusted_contacts` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | String(36) | Primary Key | UUID token |
| `owner_device_id` | String(100) | Indexed | Owner device ID |
| `contact_device_id`| String(100) | Indexed | Trusted contact device ID |
| `contact_name` | String(100) | Nullable | Display name |
| `contact_phone` | String(50) | Nullable | Phone number |
| `created_at` | DateTime(UTC) | Not Null | Pairing timestamp |

### `device_registrations` Table
| Column | Type | Constraints | Description |
|---|---|---|---|
| `device_id` | String(100) | Primary Key | Device ID |
| `fcm_token` | String(255) | Nullable | Firebase Cloud Messaging device token |
| `updated_at` | DateTime(UTC) | Not Null | Last token registration timestamp |

---

## 4. Backend Endpoints (Phase 6)

| Method | Route | Description |
|---|---|---|
| `POST` | `/api/invitations` | Creates incoming call invitation and notifies recipient |
| `GET` | `/api/invitations/{id}` | Retrieves invitation status (enforces caller/recipient authorization) |
| `GET` | `/api/invitations/pending/{recipient_id}` | Retrieves active pending invitation for recipient |
| `POST` | `/api/invitations/{id}/answer` | Accepts invitation, validates expiry, returns signed LiveKit token |
| `POST` | `/api/invitations/{id}/dismiss` | Dismisses invitation without affecting emergency event |
| `POST` | `/api/invitations/{id}/cancel` | Caller cancels pending invitation |
| `POST` | `/api/contacts/pair` | Pairs owner device with trusted contact device |
| `GET` | `/api/contacts/{device_id}` | Lists paired trusted contacts |
| `POST` | `/api/devices/fcm-token` | Registers Android device FCM token |
| `WebSocket` | `/api/ws/signaling/{device_id}` | Real-time bidirectional WebSocket signaling channel |

---

## 5. Required Android Permissions

| Permission | Protection Level | Purpose |
|---|---|---|
| `android.permission.POST_NOTIFICATIONS` | Runtime (API 33+) | Display incoming emergency call notifications on lock screen and heads-up banner |
| `android.permission.RECORD_AUDIO` | Runtime | Capture microphone audio for two-way LiveKit calling |
| `android.permission.FOREGROUND_SERVICE` | Normal | Maintain persistent call connectivity when minimized |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Normal (API 34+) | Microphone foreground service access for WebRTC call |
| `android.permission.ACCESS_FINE_LOCATION` | Runtime | GPS location acquisition during SOS |
| `android.permission.SEND_SMS` | Runtime | Dispatches cellular SIM SMS emergency alerts |
| `android.permission.INTERNET` | Normal | HTTP REST API, WebSocket signaling, WebRTC media |

---

## 6. How to Start the Services

### 1. Start Self-Hosted LiveKit Server
Open a PowerShell terminal:
```powershell
cd C:\Users\HP\AndroidStudioProjects\AEGIS\livekit
.\run-livekit.ps1
```
*Signaling Port: 7880 | ICE TCP Fallback: 7881 | ICE UDP Media Range: 50000-50100*

### 2. Start FastAPI Backend
Open a second PowerShell terminal:
```powershell
cd C:\Users\HP\AndroidStudioProjects\AEGIS\backend
.\venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
```
*Health Check: `curl http://127.0.0.1:8000/health` -> `{"status":"healthy"}`*

---

## 7. Physical Device Testing (Phone A & Phone B)

### Step 1: Network & App Setup
1. Connect Windows laptop, Phone A, and Phone B to the **same Wi-Fi network** (e.g. `192.168.1.4`).
2. Build and install the latest APK on both devices:
   `app/build/outputs/apk/debug/app-debug.apk`
3. On **Phone A**:
   - In "Device Role & Call Signaling", tap **ROLE: PHONE A**.
   - Verify WebSocket shows **CONNECTED** and FCM shows `FCM: Registered (xxxx...)`.
   - In "Emergency Recipient (Phone B)", enter Phone B's mobile number.
4. On **Phone B**:
   - In "Device Role & Call Signaling", tap **ROLE: PHONE B**.
   - Verify WebSocket shows **CONNECTED** and FCM shows `FCM: Registered (xxxx...)`.
   - If FCM displays `FCM: Standby` or `FCM: Network offline`, tap the **SYNC FCM** button to force immediate registration with the backend.
   - Grant Notification permission when prompted.

### Step 2: Test Scenario A (Phone B Foreground)
1. Keep AEGIS open on Phone B.
2. On Phone A, tap **TRIGGER MANUAL SOS**.
3. Phone B receives real-time WebSocket invitation immediately.
4. Tap **ANSWER**: Two-way LiveKit audio connects.

### Step 3: Test Scenario B (Phone B Backgrounded)
1. On Phone B, press the Home button to minimize AEGIS to the background.
2. On Phone A, tap **TRIGGER MANUAL SOS**.
3. Phone A creates emergency event and dispatches FCM push notification.
4. Phone B's `AegisFirebaseMessagingService` receives high-priority FCM data message and sounds the ringtone with vibration.
5. Tap **ANSWER** on the notification banner.
6. Android launches `MainActivity` directly via `PendingIntent.getActivity`, fetches authorized LiveKit token, and connects audio.

### Step 4: Test Scenario C (Phone B Screen Locked & Wake Behavior)
1. On Phone B, lock the screen completely (display turns off).
2. On Phone A, tap **TRIGGER MANUAL SOS**.
3. **Screen Wake & Alert Behavior**:
   - Phone B receives the high-priority FCM data message.
   - `EmergencyCallNotificationManager` fires a notification on channel `aegis_emergency_call_channel_v2` with `PRIORITY_MAX`, `CATEGORY_CALL`, ringtone, vibration, and `.setFullScreenIntent(fullScreenPendingIntent, true)`.
   - Android's WindowManager and PowerManager turn ON the screen and invoke `MainActivity` with `android:showWhenLocked="true"` and `android:turnScreenOn="true"`.
   - The full-screen incoming emergency call UI is displayed directly on the screen over the lock screen without requiring device unlock.
   - The lock screen notification banner also displays with `VISIBILITY_PUBLIC` (showing caller name, without sensitive GPS data) and provides immediate **ANSWER** and **DISMISS** action buttons.
4. Tap **ANSWER** on the screen:
   - Call is validated with FastAPI backend.
   - Authorized LiveKit token is acquired.
   - Two-way Opus audio connects immediately.
5. Alternatively, tap **DISMISS**:
   - The notification and incoming UI dismiss immediately.
   - The underlying emergency event on the server remains intact.

### Step 5: Test Scenario D (Phone B Removed from Recent Apps / Process Suspended)
1. On Phone B, swipe AEGIS away from the Recent Apps list (app process is removed/suspended).
2. On Phone A, tap **TRIGGER MANUAL SOS**.
3. FCM delivers high-priority data message to Google Play Services on Phone B, waking `AegisFirebaseMessagingService`.
4. Full-screen intent and high-priority notification appear with ringtone and vibration.
5. Tap **ANSWER**: `MainActivity` launches, restores preferences (`AegisPreferences`), validates the invitation, and connects LiveKit audio.

### Step 6: Test Scenario E (Phone B Force-Stopped by User)
1. On Phone B, go to Android Settings -> Apps -> AEGIS -> **Force Stop**.
2. **Android OS Restriction Note**: Under official Android security rules (since Android 3.1+), any app explicitly force-stopped by the user enters a "stopped state". In this state, the OS prevents broadcasts, services, and FCM messages from executing until the user manually relaunches the application from the launcher. Document this limitation; no Android application can bypass this OS security policy.

---

## 8. Android 14+ Full-Screen Intent Permissions & Settings
On Android 14+ (API 34+), Google introduced the special permission `USE_FULL_SCREEN_INTENT`.
- If a device blocks full-screen intents by default, AEGIS detects this via `EmergencyCallNotificationManager.canUseFullScreenIntent(context)`.
- An amber card is displayed in the app UI with button **OPEN SETTINGS TO ALLOW FULL-SCREEN**.
- Alternatively, on Phone B, navigate to:
  `Settings -> Apps -> Special app access -> Full screen intents -> AEGIS -> Allow`.
- Additionally, ensure lock screen notifications are set to **Show all notification content** (or "Show notifications") under:
  `Settings -> Notifications -> Notifications on lock screen`.

---

## 8. Firebase Cloud Messaging (FCM) Integration

FCM push notifications complement WebSocket signaling when Phone B is backgrounded, the screen is locked, or the app process has been suspended by Android battery optimization.

### 1. Configuration & Security
- **Firebase Project ID**: `aegis-7a6a7` (matched in `app/google-services.json` and `backend/serviceAccountKey.json`).
- **Backend Credentials**: Read from `backend/.env` via `FIREBASE_CREDENTIALS_PATH=serviceAccountKey.json`.
- **Git Security**: Excluded from git via `.gitignore` (`backend/serviceAccountKey.json` and `backend/*serviceAccount*.json`).
- **Privacy & Logging**: Credentials and full tokens are never printed to stdout or logs. Tokens are masked (e.g. `d84hW...91b`) and private keys are never exposed in API responses.

### 2. Verified Backend Endpoints
- **FCM Status**: `GET /api/fcm/status`
  ```json
  {
    "configured": true,
    "credentials_path_resolved": true,
    "initialized": true,
    "project_id": "aegis-7a6a7"
  }
  ```
- **FCM Device Registration**: `POST /api/devices/fcm-token`
  ```json
  {
    "device_id": "phone-b",
    "fcm_token": "fcm_token_string_here"
  }
  ```
- **FCM Push Test**: `POST /api/fcm/test`
  ```json
  {
    "device_id": "phone-b",
    "dry_run": false
  }
  ```
  *(Returns `{"success": true, "message_id": "projects/aegis-7a6a7/messages/..."}` on success)*

### 3. Client-Side Lifecycle & Token Sync
- **Active Sync**: On app launch or role change, `CallSignalingClient.syncFcmToken()` queries `FirebaseMessaging.getInstance().token` and immediately uploads the current registration token to `/api/devices/fcm-token`.
- **Token Rotation**: `AegisFirebaseMessagingService.onNewToken()` listens for FCM token refreshes and automatically updates the backend record.
- **Message Handling**: When an incoming call push arrives, `AegisFirebaseMessagingService.onMessageReceived()` parses the high-priority data payload and displays the incoming call notification via `EmergencyCallNotificationManager`.

### 4. End-to-End Verification Procedure on Real Devices
1. Start the FastAPI backend:
   ```powershell
   cd backend
   .\venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
   ```
2. Verify FCM status:
   ```powershell
   curl http://127.0.0.1:8000/api/fcm/status
   ```
   Ensure `"initialized": true` and `"project_id": "aegis-7a6a7"`.
3. Open AEGIS on Phone B and select **ROLE: PHONE B**.
   - Check the signaling card: `FCM: Registered (xxxx...)` indicates the token was retrieved and registered with the backend.
4. Minimize or lock Phone B.
5. Trigger a test push from the backend or another terminal:
   ```powershell
   curl -X POST http://127.0.0.1:8000/api/fcm/test -H "Content-Type: application/json" -d "{\"device_id\":\"phone-b\"}"
   ```
6. Verify Phone B rings with the incoming emergency call notification and displays Answer / Dismiss actions.

---

## 9. Known Android Lifecycle Limitations

1. **Force-Stopped Apps (`am force-stop`)**:
   Under Android security rules (since Android 3.1+), an application that has been explicitly force-stopped by the user enters a "stopped state" where no broadcasts, WebSocket connections, or standard background services can execute until the user manually relaunches the app. FCM high-priority messages also cannot wake an app in the stopped state on most OEM distributions.
2. **OEM Battery Optimization**:
   Certain manufacturers (e.g. Xiaomi MIUI, Huawei EMUI, Samsung OneUI) enforce aggressive background task killing. Users should disable battery optimization ("Unrestricted" battery usage) for AEGIS to ensure WebSocket signaling and background notifications fire immediately.
3. **Denied Notification Permission**:
   On Android 13+ (API 33+), if the user denies `POST_NOTIFICATIONS`, system heads-up notifications cannot be displayed. AEGIS detects this condition and presents an in-app banner alert while the app is foregrounded.
4. **Network Partitioning**:
   If Phone B loses Wi-Fi connectivity, the WebSocket signaling client executes exponential backoff reconnection. If a call was initiated during the network partition, Phone B retrieves any pending non-expired invitations immediately upon reconnecting via `check_pending`.
