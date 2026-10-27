# AEGIS Phase 5: Self-Hosted LiveKit App-to-App Audio Calling

## 1. Overview and Architecture

AEGIS Phase 5 adds secure, real-time, two-way audio calling between Phone A (the person triggering the emergency) and Phone B (the trusted contact responder) over a self-hosted LiveKit WebRTC server hosted on the local network.

### Architecture

```
+---------------------------------------------------------------------------------+
| Windows Laptop (Host LAN: 192.168.1.4)                                          |
|                                                                                 |
|   +------------------------------------+    +---------------------------------+ |
|   | Self-Hosted LiveKit Server         |    | FastAPI Backend                 | |
|   | Signaling Port:  7880 (HTTP / WS)  |    | HTTP API: Port 8000             | |
|   | ICE TCP Fallback: 7881 (TCP)       |    | Endpoint: POST /api/calls/token | |
|   | ICE UDP Range:   50000-50100 (UDP) |    +----------------+----------------+ |
|   +-----------------+------------------+                     |                  |
|                     ^                                        |                  |
|                     | WebRTC Media                           | Event Validation |
|                     |                                        v                  |
|                     |                       +----------------+----------------+ |
|                     |                       | PostgreSQL Database             | |
|                     |                       | Table: emergency_events         | |
|                     |                       +---------------------------------+ |
+---------------------|-----------------------------------------------------------+
                      |
        +-------------+-------------+
        |                           |
        v                           v
+-----------------------+   +-----------------------+
| Phone A (Requester)   |   | Phone B (Responder)   |
| AEGIS Android App     |   | AEGIS Android App     |
| Role: Emergency Call  |   | Role: Trusted Contact |
+-----------------------+   +-----------------------+
```

### Component Responsibilities

1. **Self-Hosted LiveKit Server (`livekit/`)**:
   - Manages WebRTC SFU (Selective Forwarding Unit) media routing.
   - Accepts WebSocket signaling connections on port `7880`.
   - Routes real-time two-way Opus audio between Phone A and Phone B over UDP (`50000-50100`) or TCP fallback (`7881`).
   - Advertises laptop LAN IPv4 (`192.168.1.4`) to peer devices on the same Wi-Fi network.

2. **FastAPI Backend (`backend/`)**:
   - Validates that caller requests are tied to an active emergency event in PostgreSQL.
   - Derives a secure, unguessable room name `emergency-{event_id}`.
   - Issues short-lived (10-minute) signed JWT tokens with room-scoped audio publication and subscription permissions.
   - Never exposes LiveKit API secret keys to Android devices.

3. **Android Application (`app/`)**:
   - **`LiveKitCallManager`**: Singleton calling controller managing token requests, room connection, track publishing, mute/unmute, participant monitoring, and clean disconnection.
   - **`EmergencyCallService`**: Android Foreground Service with `microphone` type ensuring that audio calling persists even if the app is minimized or the screen turns off.
   - **`EmergencyOrchestrator`**: Unifies calling into the existing SOS pipeline. Automatic or manual call initiation occurs alongside SMS dispatch and GPS acquisition without blocking them.
   - **`MainActivity`**: UI with live calling status badges, in-call Mute/Unmute and Leave Call controls, and a dedicated **Phone B Responder Mode** allowing trusted contacts to join with the Event ID from the SMS alert.

---

## 2. Port Requirements & Security

| Port / Protocol | Direction | Purpose | Why It Is Required |
|---|---|---|---|
| `8000 / TCP` | Inbound to Laptop | FastAPI HTTP REST API | Emergency creation and LiveKit call token requests |
| `7880 / TCP` | Inbound to Laptop | LiveKit HTTP & WebSocket Signaling | LiveKit connection handshake, participant exchange |
| `7881 / TCP` | Inbound to Laptop | LiveKit ICE / TCP Fallback | WebRTC media transmission when UDP is blocked by network |
| `50000-50100 / UDP` | Inbound to Laptop | LiveKit WebRTC Media Traffic | Low-latency real-time Opus audio transmission |

> [!IMPORTANT]
> The Windows Firewall must permit inbound traffic on ports `8000/TCP`, `7880/TCP`, `7881/TCP`, and `50000-50100/UDP` from devices on your local private Wi-Fi network.

---

## 3. How to Find Your Laptop's Current LAN IPv4 Address

Before starting services or testing on real Android phones:

1. Open PowerShell on the Windows laptop:
   ```powershell
   Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -match "Wi-Fi" } | Select-Object IPAddress
   ```
2. Note your Wi-Fi IPv4 address (e.g. `192.168.1.4`).
3. If your IP changes, update `livekit/livekit.yaml` (`node_ip: "<YOUR_IP>"`) and `backend/.env` (`LIVEKIT_URL=ws://<YOUR_IP>:7880`).
4. In the Android app, you can update the backend URL directly in the **Backend & LiveKit Host Configuration** card.

---

## 4. Starting the Services

### Option A: Native Windows (No Docker Required)

1. **Start the LiveKit Server**:
   Open a PowerShell terminal:
   ```powershell
   cd C:\Users\HP\AndroidStudioProjects\AEGIS\livekit
   .\run-livekit.ps1
   ```
   *Expected output*:
   ```
   Starting Self-Hosted AEGIS LiveKit Server
   Config: C:\Users\HP\AndroidStudioProjects\AEGIS\livekit\livekit.yaml
   Signaling: http://192.168.1.4:7880
   ```
   To verify health: `curl http://127.0.0.1:7880` -> Returns `OK`.

2. **Start the FastAPI Backend**:
   Open a second PowerShell terminal:
   ```powershell
   cd C:\Users\HP\AndroidStudioProjects\AEGIS\backend
   .\venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
   ```
   To verify health: `curl http://127.0.0.1:8000/health` -> Returns `{"status":"healthy"}`.

### Option B: Docker Compose (If Docker Desktop is installed)

1. Navigate to the `livekit` directory:
   ```powershell
   cd C:\Users\HP\AndroidStudioProjects\AEGIS\livekit
   docker compose up -d
   ```
2. Start FastAPI as shown above.

---

## 5. Stopping the Services Cleanly

- **LiveKit Server (Native)**: Press `Ctrl + C` in the LiveKit terminal window.
- **FastAPI Backend**: Press `Ctrl + C` in the backend terminal window.
- **Docker Compose (if used)**: `docker compose down`

---

## 6. Physical Device Testing Checklist (Phone A & Phone B)

Follow these exact steps to test two-way audio calling between two physical Android devices:

### Step 1: Network Preparation
- [ ] Connect the Windows laptop, Phone A, and Phone B to the **exact same Wi-Fi network**.
- [ ] Verify laptop LAN IP (e.g., `192.168.1.4`).
- [ ] Start LiveKit server (`livekit/run-livekit.ps1`) on laptop.
- [ ] Start FastAPI backend (`uvicorn main:app --host 0.0.0.0 --port 8000`) on laptop.

### Step 2: Install and Configure AEGIS on Both Phones
- [ ] Build and install debug APK on both phones:
  `app/build/outputs/apk/debug/app-debug.apk`
- [ ] On Phone A and Phone B: In **Backend & LiveKit Host Configuration**, verify backend URL is `http://192.168.1.4:8000/`.
- [ ] On Phone A: Set **Emergency Recipient (Phone B)** to Phone B's mobile number.

### Step 3: Trigger Emergency from Phone A
- [ ] Tap **TRIGGER MANUAL SOS** (or say `"I need help"` with Voice Protection enabled).
- [ ] Verify that:
  - GPS location is acquired.
  - Emergency event is persisted in PostgreSQL database with an Event ID (e.g., `3fa85f64...`).
  - SMS alert is dispatched to Phone B via cellular SIM containing `Event: <event_id>` and Google Maps link.
  - Card 4 transitions to **GETTING TOKEN** -> **CONNECTING** -> **WAITING FOR PEER**.

### Step 4: Join Emergency Audio Call from Phone B
- [ ] On Phone B: View received emergency SMS containing `Event: <event_id>`.
- [ ] In the AEGIS app on Phone B: Scroll to **Trusted Contact Mode (Phone B Responder)**.
- [ ] Paste or type the Event ID into the **Emergency Event ID** field.
- [ ] Tap **JOIN EMERGENCY CALL AS PHONE B**.
- [ ] Grant Microphone permission when prompted.
- [ ] On Phone B: Status transitions to **CONNECTED (2-WAY)**.
- [ ] On Phone A: Status transitions to **CONNECTED (2-WAY)** with badge `"Connected with: Phone B (Trusted Contact)"`.

### Step 5: Test In-Call Audio & Controls
- [ ] Speak into Phone A: Verify clear audio is heard through Phone B's speaker.
- [ ] Speak into Phone B: Verify clear audio is heard through Phone A's speaker.
- [ ] On Phone A: Tap **MUTE MIC**. Verify Phone B can no longer hear Phone A. Tap **UNMUTE MIC** to restore audio.
- [ ] Background the app on either phone: Verify the foreground notification persists and call audio continues uninterrupted.

### Step 6: End Call and Failure Resilience Tests
- [ ] Tap **LEAVE CALL** on Phone B: Verify Phone A updates to `"In room. Waiting for other party to join..."`.
- [ ] Tap **LEAVE CALL** on Phone A: Verify call state transitions to **ENDED**.
- [ ] Verify that ending the call leaves the emergency GPS coordinates, SMS history, and backend event records intact.
- [ ] Test stopping LiveKit server during an active emergency: Verify the app displays `"Call connection error"`, but manual SOS, voice protection, SMS alerts, and GPS tracking remain 100% operational.

---

## 7. Known Limitations and Boundary Notes

1. **Same-LAN Wi-Fi Scope**:
   Phase 5 establishes local development connectivity over Wi-Fi using the laptop's private LAN IPv4. Calling across separate mobile cellular data networks or symmetric NATs requires a public STUN/TURN server and domain TLS certificates (`wss://`), which is outside the scope of Phase 5.
2. **Signaling & Incoming Call Notifications**:
   AEGIS Phase 5 provides an honest, reliable joining mechanism where Phone B joins using the Event ID delivered in the emergency SMS. Automatic incoming call push notification signaling (e.g. via Firebase Cloud Messaging or WebSockets) is scheduled for Phase 6. Fake ringing states are deliberately not implemented.
3. **No Audio Recording**:
   As required for privacy and safety, audio streams are live two-way WebRTC only and are never stored or recorded.
