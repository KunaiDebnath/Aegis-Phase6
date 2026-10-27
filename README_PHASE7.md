# AEGIS Phase 7: Integration and Failure Handling Guide

## 1. Overview and Architecture Resiliency

AEGIS Phase 7 hardens all system integration points between the Android client, FastAPI backend, PostgreSQL database, LiveKit WebRTC server, and Firebase Cloud Messaging (FCM).

The central design principle in Phase 7 is **Subsystem Isolation**: a failure, timeout, disconnection, or rejection in one emergency component (such as GPS satellite timeout, LiveKit media server downtime, cellular carrier delay, or backend HTTP 503) must **never abort, suppress, or disable unrelated life-safety functions**.

```
+------------------------------------------------------------------------------------------+
|                                    AEGIS Architecture                                    |
|                                                                                          |
|  +--------------------+     +------------------------+     +--------------------------+  |
|  |  Subsystem: GPS    |     |  Subsystem: Backend    |     |  Subsystem: Telephony    |  |
|  |  7s hard timeout   |     |  PostgreSQL DB Sync    |     |  Carrier SIM SMS         |  |
|  |  Fallback: (No GPS)|     |  Fallback: Offline Ref |     |  Always attempted        |  |
|  +---------+----------+     +-----------+------------+     +------------+-------------+  |
|            |                            |                               |                |
|            +----------------------------+-------------------------------+                |
|                                         |                                                |
|                                         v                                                |
|                      +--------------------------------------+                            |
|                      |         EmergencyOrchestrator        |                            |
|                      |  Atomic acquisition & debounce guard |                            |
|                      +------------------+-------------------+                            |
|                                         |                                                |
|                                         v                                                |
|                      +--------------------------------------+                            |
|                      |       Two-Way Safety Calling         |                            |
|                      |   LiveKit WebRTC + WebSocket + FCM   |                            |
|                      +--------------------------------------+                            |
+------------------------------------------------------------------------------------------+
```

---

## 2. Failure Isolation & Degraded Modes Matrix

| Subsystem | Failure Condition | Graceful Degradation / Fallback Behavior | Life-Safety Guarantee |
|---|---|---|---|
| **GPS Location** | Satellites unavailable, indoor timeout (>7s), or permission denied | `LocationAcquisitionStatus.TIMEOUT` / `UNAVAILABLE`. SMS alert formats as `(Location unavailable)` without coordinates. Backend records `null` latitude/longitude. | Backend sync and cellular SMS alert proceed immediately without blocking. |
| **Backend (FastAPI / DB)** | Network offline, connection timeout, or HTTP 500/503 | `BackendSyncStatus.FAILED`. Emergency SMS falls back to formatting with idempotency key prefix: `Ref: <first-8-chars>`. User can tap **RETRY BACKEND** once online. | Emergency SMS is dispatched via carrier cellular SIM regardless of backend availability. |
| **LiveKit Calling** | LiveKit server offline, auth token rejected (503), or microphone missing | `LiveKitCallManager` transitions to `CallState.FAILED`, WebRTC resources are cleaned up, and `MicrophoneCoordinator` restores voice recognition. | SMS, GPS, and backend emergency event remain intact in database. |
| **FCM Push Notifications** | Google FCM error, invalid device token, or Google Play Services missing | Backend logs failure (`fcm_status: failed: <detail>`) and automatically clears invalid tokens. Delivers invitation via WebSocket signaling. | Call invitation record is created in PostgreSQL and delivered via real-time WebSocket. |
| **Signaling Network Loss** | Wi-Fi dropped, network partition, or connection closed | `CallSignalingClient` executes progressive backoff (3s, 6s, 10s, 15s). On reconnection (`onOpen`), immediately checks for pending invitations (`check_pending`) and resyncs FCM token. | Stored pending invitations are retrieved immediately upon network reconnection. |
| **Responder Dismissal** | Phone B taps **DISMISS** on incoming call | Call invitation transitions to `dismissed`. Notification is cancelled on Phone B. WebSocket alerts Phone A. | **Critical Safety Guarantee**: The underlying `EmergencyEvent` **remains active in PostgreSQL**. Emergency SMS and GPS coordinates are never resolved or cancelled. |
| **Call Expiry (Timeout)** | 60-second timer elapses without Phone B answering | Invitation status transitions to `expired`. Any late answer attempt returns HTTP 400 ("Call invitation has expired"). Notification is cancelled. | Underlying emergency remains active; Phone A can re-invite Phone B. |

---

## 3. Concurrency and Idempotency Guarantees

1. **Android Debounce & Atomic Acquisition Guard (`EmergencyTriggerGuard`)**:
   - `tryAcquireTrigger()` is synchronized and atomic.
   - Rapid double-tapping of the Manual SOS button is blocked by a 5-second debounce window (`DEBOUNCE_COOLDOWN_MS = 5000L`).
   - Simultaneous voice recognition detection (`"I need help"`) and manual button taps cannot trigger parallel duplicate orchestration workflows.
   - An active emergency session remains protected until the user explicitly taps "NEW SOS EVENT".

2. **Backend Concurrency Race Condition Guard (`POST /api/emergencies`)**:
   - `idempotency_key` has a unique constraint in PostgreSQL.
   - If two requests with the same idempotency key hit the database simultaneously, `IntegrityError` is caught, rolled back, and the existing canonical record is returned with status 201 (`"message": "Emergency event already exists"`).

3. **Backend Active Call Guard (`POST /api/invitations`)**:
   - If an invitation is already `pending` and not expired, the existing invitation is returned.
   - If Phone B has already `accepted` the call and the call is in progress, repeated invitation requests return the existing active invitation (`"message": "Call invitation already accepted and active"`), preventing duplicate WebRTC rooms.

4. **Phone B Notification & Timer Stability**:
   - Notification ID is deterministically computed from `invitationId.hashCode()`, preventing stacked duplicate notifications.
   - If Phone B receives both WebSocket and FCM notifications for the same invitation ID, `CallSignalingClient` preserves the existing countdown timer rather than bouncing back to 60 seconds.

---

## 4. Automated Verification & Test Results

### 1. Backend Automated Tests (33 Passed)
Includes new test suite `test_failure_handling.py`:
- `test_emergency_creation_concurrent_integrity_error_handled`
- `test_invitation_creation_when_call_already_accepted_is_idempotent`
- `test_database_health_failure_returns_503`
- `test_subsystem_isolation_emergency_event_persists_across_call_lifecycle`
- `test_fcm_failure_does_not_block_invitation_creation`
- `test_livekit_unconfigured_error_isolation`

**Command to run backend tests**:
```powershell
cd C:\Users\HP\AndroidStudioProjects\AEGIS\backend
.\venv\Scripts\python.exe -m unittest discover
```
*Result: 33 tests executed, 0 failures, 100% OK.*

### 2. Android Automated Unit Tests (54 Passed)
Includes new test suite `IntegrationFailureHandlingUnitTest.kt`:
- `testDuplicateTriggerGuard_rapidConcurrentTriggersBlocked`
- `testEmergencySmsFormatting_whenBackendAndGpsBothFail`
- `testEmergencySmsFormatting_whenBackendFailsButGpsSucceeds`
- `testEmergencySmsFormatting_whenBackendSucceedsButGpsFails`
- `testIncomingInvitation_duplicatePayloadDoesNotResetCountdownTimer`
- `testIncomingInvitation_newInvitationReplacesExpiredOne`
- `testSanitizeEventId_robustHandlingOfCopiedText`
- `testIsValidPhoneNumber_validFormats`

**Command to run Android tests**:
```powershell
cd C:\Users\HP\AndroidStudioProjects\AEGIS
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = ($env:PATH -split ';' | Where-Object { $_ -notmatch 'cmake' -and $_ -notmatch '"' }) -join ';'
.\gradlew.bat testDebugUnitTest
```
*Result: 54 tests executed, 0 failures, BUILD SUCCESSFUL.*

### 3. Debug APK Compilation
```powershell
.\gradlew.bat assembleDebug
```
*Output: `app/build/outputs/apk/debug/app-debug.apk` (BUILD SUCCESSFUL).*

---

## 5. Physical Device Test Procedures (For Phone A & Phone B)

The following procedures verify integration and failure scenarios on physical hardware.

### Prerequisites
1. Start the LiveKit server:
   ```powershell
   cd C:\Users\HP\AndroidStudioProjects\AEGIS\livekit
   .\run-livekit.ps1
   ```
2. Start the FastAPI backend:
   ```powershell
   cd C:\Users\HP\AndroidStudioProjects\AEGIS\backend
   .\venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
   ```
3. Install the latest APK on both Phone A and Phone B:
   `app/build/outputs/apk/debug/app-debug.apk`

---

### Physical Test 1: Duplicate SOS Triggers & Debounce
1. Open AEGIS on Phone A (Role: Phone A).
2. Tap the **TRIGGER MANUAL SOS** button 5 times rapidly in succession.
3. **Expected Behavior**:
   - Only ONE emergency event is created.
   - Only ONE cellular SMS is sent.
   - UI status card displays the active emergency and disables repeat triggering.
   - Subsequent rapid taps within 5 seconds are safely rejected.

---

### Physical Test 2: Network Loss & Reconnection
1. On Phone B (Role: Phone B), verify signaling displays `Signaling: Connected (phone-b)`.
2. Disable Wi-Fi and mobile data on Phone B (Airplane Mode).
3. Verify status displays `Signaling offline (Network error)`.
4. On Phone A, trigger an emergency SOS.
5. While Phone B is offline, re-enable Wi-Fi on Phone B.
6. **Expected Behavior**:
   - `CallSignalingClient` reconnects automatically.
   - As soon as signaling connects, Phone B queries pending invitations via `check_pending`.
   - The incoming call alert appears with the remaining valid time on the countdown timer.

---

### Physical Test 3: Backend Offline Resilience
1. Temporarily stop the FastAPI backend (Ctrl+C in backend terminal).
2. On Phone A, tap **TRIGGER MANUAL SOS**.
3. **Expected Behavior**:
   - GPS fix succeeds (or times out).
   - Backend sync reports `Backend unavailable: Network error`.
   - Telephony SMS is STILL sent successfully to Phone B with the fallback reference `Ref: <idempotency_prefix>`.
4. Restart the FastAPI backend.
5. On Phone A, tap **RETRY BACKEND**.
6. **Expected Behavior**:
   - Backend sync recovers and records the event.
   - LiveKit audio call starts.
   - Incoming call invitation is dispatched to Phone B.

---

### Physical Test 4: LiveKit Server Down Resilience
1. Stop the LiveKit server process. Keep FastAPI backend running.
2. On Phone A, tap **TRIGGER MANUAL SOS**.
3. **Expected Behavior**:
   - GPS fix is captured.
   - Backend records the emergency event.
   - SMS alert is delivered.
   - LiveKit call shows `Token authorization failed` or `Call connection error`.
   - App does NOT crash; microphone is released cleanly; speech recognition coordinator restores normal state.

---

### Physical Test 5: Call Dismissal Safety Guarantee
1. Ensure both devices are connected.
2. On Phone A, trigger an emergency SOS.
3. Phone B receives the incoming call notification and taps **DISMISS**.
4. **Expected Behavior**:
   - Phone B incoming call notification and full-screen alert dismiss immediately.
   - Phone A receives notification: `phone-b dismissed the call invitation. (Emergency alert remains active)`.
   - Verify on backend (`curl http://127.0.0.1:8000/api/emergencies/<event-id>`) that the event status is still `"active"`.
