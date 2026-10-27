import os
import re
import uuid
import logging
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect, Query, Path, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select, and_
from sqlalchemy.exc import SQLAlchemyError, IntegrityError
from livekit import api as livekit_api

from database import Base, SessionLocal, check_database_connection, engine
from models import EmergencyEvent, TrustedContact, CallInvitation, DeviceRegistration
from signaling import signaling_manager
from fcm_notifier import send_fcm_call_invitation, init_firebase_admin, get_fcm_status
from discovery import advertiser, detect_lan_ip

logger = logging.getLogger("aegis")

app = FastAPI(
    title="AEGIS API",
    description="Emergency Safety and Alert System - Phase 8",
    version="0.8.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def startup_services():
    Base.metadata.create_all(bind=engine)
    init_firebase_admin()
    try:
        advertiser.start()
    except Exception as e:
        logger.warning(f"Could not start discovery advertiser: {e}")


@app.on_event("shutdown")
def shutdown_services():
    try:
        advertiser.stop()
    except Exception as e:
        logger.warning(f"Error stopping discovery advertiser: {e}")


# ---------------------------------------------------------
# Pydantic Request / Response Models
# ---------------------------------------------------------

class SOSRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=100)
    idempotency_key: str = Field(min_length=1, max_length=64)
    trigger_source: str = Field(default="manual", pattern="^(manual|voice)$")
    latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    location_accuracy_meters: float | None = Field(default=None, ge=0.0)
    location_timestamp: datetime | None = None


class CallTokenRequest(BaseModel):
    event_id: str = Field(min_length=36, max_length=64, description="Authorized emergency event ID (UUID)")
    device_id: str = Field(min_length=1, max_length=100, description="Calling device identifier")
    participant_name: str | None = Field(default=None, max_length=100, description="Display name for participant")

    @field_validator("event_id", mode="before")
    @classmethod
    def validate_event_id(cls, v: str) -> str:
        if not isinstance(v, str):
            raise ValueError("event_id must be a string")
        clean = v.strip()
        try:
            parsed_uuid = uuid.UUID(clean)
            canonical = str(parsed_uuid)
            if clean.lower() != canonical:
                raise ValueError("event_id must be a canonical 36-character UUID string (8-4-4-4-12)")
            return canonical
        except Exception:
            raise ValueError("event_id must be a valid 36-character canonical UUID")


class CallTokenResponse(BaseModel):
    server_url: str
    token: str
    room_name: str
    identity: str
    expires_in_seconds: int


def validate_phone_number_format(phone: str | None) -> str:
    if not phone or not isinstance(phone, str):
        raise ValueError("Phone number must be a valid non-empty string")
    clean = phone.strip()
    if not re.match(r"^\+?[0-9\s\-]{7,20}$", clean):
        raise ValueError("Invalid phone number format. Allowed characters: digits, spaces, hyphens, optional leading '+'.")
    digits = [c for c in clean if c.isdigit()]
    if not (7 <= len(digits) <= 15):
        raise ValueError(f"Phone number must contain between 7 and 15 digits (got {len(digits)}).")
    return clean


class EmergencyContactUpdateRequest(BaseModel):
    owner_device_id: str | None = Field(default=None, max_length=100)
    phone_number: str = Field(min_length=7, max_length=20)
    contact_name: str | None = Field(default="Phone B", max_length=100)

    @field_validator("phone_number")
    @classmethod
    def validate_phone(cls, v: str) -> str:
        return validate_phone_number_format(v)


class EmergencyContactResponse(BaseModel):
    owner_device_id: str
    contact_device_id: str
    contact_name: str | None = None
    phone_number: str
    is_configured: bool = True
    message: str | None = None
    updated_at: str | None = None


class PairContactRequest(BaseModel):
    owner_device_id: str = Field(min_length=1, max_length=100)
    contact_device_id: str = Field(min_length=1, max_length=100)
    contact_name: str | None = Field(default=None, max_length=100)
    contact_phone: str | None = Field(default=None, max_length=50)

    @field_validator("contact_phone")
    @classmethod
    def validate_phone(cls, v: str | None) -> str | None:
        if v is None:
            return None
        return validate_phone_number_format(v)


class CreateInvitationRequest(BaseModel):
    emergency_event_id: str = Field(min_length=36, max_length=64)
    caller_device_id: str = Field(min_length=1, max_length=100)
    recipient_device_id: str = Field(min_length=1, max_length=100)
    caller_display_name: str | None = Field(default=None, max_length=100)
    timeout_seconds: int = Field(default=60, ge=10, le=300)

    @field_validator("emergency_event_id", mode="before")
    @classmethod
    def validate_event_id(cls, v: str) -> str:
        if not isinstance(v, str):
            raise ValueError("emergency_event_id must be a string")
        clean = v.strip()
        try:
            parsed_uuid = uuid.UUID(clean)
            canonical = str(parsed_uuid)
            if clean.lower() != canonical:
                raise ValueError("emergency_event_id must be a canonical 36-character UUID string")
            return canonical
        except Exception:
            raise ValueError("emergency_event_id must be a valid canonical UUID")


class AnswerInvitationRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=100)
    participant_name: str | None = Field(default=None, max_length=100)


class DismissInvitationRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=100)


class CancelInvitationRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=100)


class RegisterFcmTokenRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=100)
    fcm_token: str = Field(min_length=10, max_length=1024)


class FcmTestRequest(BaseModel):
    device_id: str | None = Field(default=None, max_length=100)
    fcm_token: str | None = Field(default=None, max_length=1024)
    dry_run: bool = Field(default=False)


# ---------------------------------------------------------
# Helper Functions
# ---------------------------------------------------------

def get_resolved_livekit_url() -> str:
    """
    Returns the reachable LiveKit WebSocket URL.
    Dynamically falls back to active laptop LAN IP if LIVEKIT_URL is 'auto'.
    Respects empty string if explicitly cleared in test environments.
    """
    if "LIVEKIT_URL" in os.environ and os.environ["LIVEKIT_URL"] == "":
        return ""
    configured = (os.getenv("LIVEKIT_URL") or "").strip()
    current_ip = detect_lan_ip()
    if not configured or configured.lower() == "auto":
        return f"ws://{current_ip}:7880"
    if "192.168.1.4" in configured and current_ip != "192.168.1.4" and not current_ip.startswith("127."):
        logger.info(f"Dynamically updating legacy LIVEKIT_URL {configured} to active interface: ws://{current_ip}:7880")
        return f"ws://{current_ip}:7880"
    return configured


def generate_livekit_token(
    event_id: str,
    device_id: str,
    participant_name: str | None = None,
) -> CallTokenResponse:
    livekit_url = get_resolved_livekit_url()
    api_key = os.getenv("LIVEKIT_API_KEY")
    api_secret = os.getenv("LIVEKIT_API_SECRET")

    if not livekit_url or not api_key or not api_secret:
        raise HTTPException(
            status_code=503,
            detail="LiveKit calling service is not configured on backend",
        )

    room_name = f"emergency-{event_id}"
    clean_device_id = device_id.strip()
    name = (participant_name or clean_device_id).strip()

    grant = livekit_api.VideoGrants(
        room_join=True,
        room=room_name,
        can_publish=True,
        can_subscribe=True,
        can_publish_data=True,
    )

    token = (
        livekit_api.AccessToken(api_key, api_secret)
        .with_identity(clean_device_id)
        .with_name(name)
        .with_grants(grant)
        .with_ttl(timedelta(minutes=10))
        .to_jwt()
    )

    return CallTokenResponse(
        server_url=livekit_url,
        token=token,
        room_name=room_name,
        identity=clean_device_id,
        expires_in_seconds=600,
    )


# ---------------------------------------------------------
# Base & Health Endpoints
# ---------------------------------------------------------

@app.get("/")
def home():
    return {
        "app": "AEGIS",
        "message": "AEGIS backend is running",
        "status": "online",
        "phase": 6,
    }


@app.get("/health")
def health_check():
    return {
        "status": "healthy",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/health/database")
def database_health():
    try:
        check_database_connection()
        return {"database": "connected", "status": "healthy"}
    except (SQLAlchemyError, Exception) as e:
        logger.warning(f"Database health check failed: {e}")
        raise HTTPException(status_code=503, detail="Database connection failed")


@app.get("/api/discovery")
def get_service_discovery():
    """
    Authoritative service discovery endpoint for AEGIS Android clients.
    Returns active LAN IP, API base URL, and dynamic LiveKit WebSocket URL.
    """
    return advertiser.get_discovery_payload()


# ---------------------------------------------------------
# Emergency Event Endpoints
# ---------------------------------------------------------

@app.post("/api/emergencies", status_code=201)
def create_emergency(request: SOSRequest):
    try:
        with SessionLocal() as db:
            existing = db.scalar(
                select(EmergencyEvent).where(
                    EmergencyEvent.idempotency_key == request.idempotency_key
                )
            )

            if existing:
                return {
                    "event_id": existing.id,
                    "status": existing.status,
                    "message": "Emergency event already exists",
                    "trigger_source": existing.trigger_source,
                    "latitude": existing.latitude,
                    "longitude": existing.longitude,
                    "location_accuracy_meters": existing.location_accuracy_meters,
                    "location_timestamp": (
                        existing.location_timestamp.isoformat()
                        if existing.location_timestamp
                        else None
                    ),
                }

            event = EmergencyEvent(
                idempotency_key=request.idempotency_key,
                device_id=request.device_id,
                trigger_source=request.trigger_source,
                status="active",
                latitude=request.latitude,
                longitude=request.longitude,
                location_accuracy_meters=request.location_accuracy_meters,
                location_timestamp=request.location_timestamp,
            )

            db.add(event)
            db.commit()
            db.refresh(event)

            return {
                "event_id": event.id,
                "status": event.status,
                "message": "Emergency event created",
                "trigger_source": event.trigger_source,
                "latitude": event.latitude,
                "longitude": event.longitude,
                "location_accuracy_meters": event.location_accuracy_meters,
                "location_timestamp": (
                    event.location_timestamp.isoformat()
                    if event.location_timestamp
                    else None
                ),
            }

    except IntegrityError:
        # Concurrent request with identical idempotency_key race condition handled gracefully
        logger.info(f"IntegrityError on create_emergency: retrieving existing event for idempotency_key={request.idempotency_key}")
        with SessionLocal() as db:
            existing = db.scalar(
                select(EmergencyEvent).where(
                    EmergencyEvent.idempotency_key == request.idempotency_key
                )
            )
            if existing:
                return {
                    "event_id": existing.id,
                    "status": existing.status,
                    "message": "Emergency event already exists",
                    "trigger_source": existing.trigger_source,
                    "latitude": existing.latitude,
                    "longitude": existing.longitude,
                    "location_accuracy_meters": existing.location_accuracy_meters,
                    "location_timestamp": (
                        existing.location_timestamp.isoformat()
                        if existing.location_timestamp
                        else None
                    ),
                }
        raise HTTPException(
            status_code=503,
            detail="Could not save the emergency event",
        )
    except SQLAlchemyError as e:
        logger.error(f"SQLAlchemyError creating emergency event: {e}")
        raise HTTPException(
            status_code=503,
            detail="Could not save the emergency event",
        )


@app.get("/api/emergencies/{event_id}")
def get_emergency(event_id: str = Path(min_length=1, max_length=64)):
    with SessionLocal() as db:
        event = db.get(EmergencyEvent, event_id)

        if event is None:
            raise HTTPException(status_code=404, detail="Emergency event not found")

        return {
            "event_id": event.id,
            "device_id": event.device_id,
            "trigger_source": event.trigger_source,
            "status": event.status,
            "created_at": (
                event.created_at.isoformat() if event.created_at else None
            ),
            "latitude": event.latitude,
            "longitude": event.longitude,
            "location_accuracy_meters": event.location_accuracy_meters,
            "location_timestamp": (
                event.location_timestamp.isoformat()
                if event.location_timestamp
                else None
            ),
        }


# ---------------------------------------------------------
# LiveKit Token Direct Endpoint (Phase 5 Compatibility)
# ---------------------------------------------------------

@app.post("/api/calls/token", response_model=CallTokenResponse)
def get_call_token(request: CallTokenRequest):
    livekit_url = get_resolved_livekit_url()
    api_key = os.getenv("LIVEKIT_API_KEY")
    api_secret = os.getenv("LIVEKIT_API_SECRET")

    if not livekit_url or not api_key or not api_secret:
        raise HTTPException(
            status_code=503,
            detail="LiveKit calling service is not configured on backend",
        )

    try:
        with SessionLocal() as db:
            event = db.get(EmergencyEvent, request.event_id)
            if event is None:
                raise HTTPException(
                    status_code=404,
                    detail="Emergency event not found or unauthorized",
                )
            if event.status != "active":
                raise HTTPException(
                    status_code=400,
                    detail="Emergency event is not active",
                )

            return generate_livekit_token(
                event_id=event.id,
                device_id=request.device_id,
                participant_name=request.participant_name,
            )
    except HTTPException:
        raise
    except SQLAlchemyError:
        raise HTTPException(
            status_code=503,
            detail="Database error while validating emergency call",
        )
    except Exception:
        raise HTTPException(
            status_code=500,
            detail="Failed to generate call authorization token",
        )


@app.get("/api/calls/{event_id}")
def get_call_info(event_id: str = Path(min_length=1, max_length=64)):
    with SessionLocal() as db:
        event = db.get(EmergencyEvent, event_id)
        if event is None:
            raise HTTPException(status_code=404, detail="Emergency event not found")

        return {
            "event_id": event.id,
            "room_name": f"emergency-{event.id}",
            "status": event.status,
            "can_call": event.status == "active",
        }


# ---------------------------------------------------------
# Trusted Contact Management (Phase 6)
# ---------------------------------------------------------

@app.post("/api/contacts/pair", status_code=200)
def pair_trusted_contact(request: PairContactRequest):
    owner = request.owner_device_id.strip()
    contact = request.contact_device_id.strip()

    if owner == contact:
        raise HTTPException(status_code=400, detail="Device cannot pair with itself as trusted contact")

    with SessionLocal() as db:
        existing = db.scalar(
            select(TrustedContact).where(
                and_(
                    TrustedContact.owner_device_id == owner,
                    TrustedContact.contact_device_id == contact,
                )
            )
        )

        if existing:
            existing.contact_name = request.contact_name or existing.contact_name
            existing.contact_phone = request.contact_phone or existing.contact_phone
            existing.updated_at = datetime.now(timezone.utc)
            db.commit()
            db.refresh(existing)
            return {
                "id": existing.id,
                "owner_device_id": existing.owner_device_id,
                "contact_device_id": existing.contact_device_id,
                "contact_name": existing.contact_name,
                "contact_phone": existing.contact_phone,
                "message": "Trusted contact pairing updated",
            }

        new_contact = TrustedContact(
            owner_device_id=owner,
            contact_device_id=contact,
            contact_name=request.contact_name,
            contact_phone=request.contact_phone,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
        )
        db.add(new_contact)
        db.commit()
        db.refresh(new_contact)

        return {
            "id": new_contact.id,
            "owner_device_id": new_contact.owner_device_id,
            "contact_device_id": new_contact.contact_device_id,
            "contact_name": new_contact.contact_name,
            "contact_phone": new_contact.contact_phone,
            "message": "Trusted contact paired successfully",
        }


@app.get("/api/contacts/{device_id}")
def get_trusted_contacts(device_id: str = Path(min_length=1, max_length=100)):
    clean_id = device_id.strip()
    with SessionLocal() as db:
        contacts = db.scalars(
            select(TrustedContact).where(TrustedContact.owner_device_id == clean_id)
        ).all()
        return [
            {
                "id": c.id,
                "owner_device_id": c.owner_device_id,
                "contact_device_id": c.contact_device_id,
                "contact_name": c.contact_name,
                "contact_phone": c.contact_phone,
                "created_at": c.created_at.isoformat(),
                "updated_at": c.updated_at.isoformat() if c.updated_at else c.created_at.isoformat(),
            }
            for c in contacts
        ]


@app.get("/api/contacts/{owner_device_id}/emergency", response_model=EmergencyContactResponse)
@app.get("/api/emergency-contact/{owner_device_id}", response_model=EmergencyContactResponse)
def get_emergency_contact(
    owner_device_id: str = Path(min_length=1, max_length=100),
    requesting_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    device_id: str | None = Query(default=None, max_length=100),
):
    clean_owner = owner_device_id.strip()
    auth_dev = (requesting_device_id or device_id or "").strip()
    if auth_dev and auth_dev != clean_owner:
        raise HTTPException(status_code=403, detail="Unauthorized to access this emergency contact")

    with SessionLocal() as db:
        # Check Phone B pairing first
        contact = db.scalar(
            select(TrustedContact).where(
                and_(
                    TrustedContact.owner_device_id == clean_owner,
                    TrustedContact.contact_device_id == "phone-b",
                )
            )
        )
        if not contact or not contact.contact_phone:
            # Fall back to any configured contact with a phone number for this device
            contact = db.scalar(
                select(TrustedContact).where(
                    and_(
                        TrustedContact.owner_device_id == clean_owner,
                        TrustedContact.contact_phone.is_not(None),
                    )
                ).order_by(TrustedContact.created_at.asc())
            )

        if not contact or not contact.contact_phone:
            raise HTTPException(status_code=404, detail="Emergency contact not configured")

        return EmergencyContactResponse(
            owner_device_id=contact.owner_device_id,
            contact_device_id=contact.contact_device_id,
            contact_name=contact.contact_name,
            phone_number=contact.contact_phone,
            is_configured=True,
            updated_at=(
                contact.updated_at.isoformat()
                if contact.updated_at
                else contact.created_at.isoformat()
            ),
        )


def _handle_set_emergency_contact(
    target_owner: str,
    request: EmergencyContactUpdateRequest,
    auth_dev: str = "",
) -> EmergencyContactResponse:
    owner = target_owner.strip()
    clean_auth = auth_dev.strip()
    if clean_auth and clean_auth != owner:
        raise HTTPException(status_code=403, detail="Unauthorized to modify this emergency contact")

    if owner == "phone-b":
        raise HTTPException(status_code=400, detail="Device cannot pair with itself as Phone B emergency contact")

    with SessionLocal() as db:
        existing = db.scalar(
            select(TrustedContact).where(
                and_(
                    TrustedContact.owner_device_id == owner,
                    TrustedContact.contact_device_id == "phone-b",
                )
            )
        )

        now = datetime.now(timezone.utc)
        if existing:
            existing.contact_phone = request.phone_number
            if request.contact_name:
                existing.contact_name = request.contact_name
            existing.updated_at = now
            db.commit()
            db.refresh(existing)
            logger.info(f"Updated Phone B emergency contact for owner '{owner}'")
            return EmergencyContactResponse(
                owner_device_id=existing.owner_device_id,
                contact_device_id=existing.contact_device_id,
                contact_name=existing.contact_name,
                phone_number=existing.contact_phone,
                is_configured=True,
                message="Emergency contact updated successfully",
                updated_at=existing.updated_at.isoformat(),
            )

        new_contact = TrustedContact(
            owner_device_id=owner,
            contact_device_id="phone-b",
            contact_name=request.contact_name or "Phone B",
            contact_phone=request.phone_number,
            created_at=now,
            updated_at=now,
        )
        db.add(new_contact)
        db.commit()
        db.refresh(new_contact)
        logger.info(f"Created Phone B emergency contact for owner '{owner}'")
        return EmergencyContactResponse(
            owner_device_id=new_contact.owner_device_id,
            contact_device_id=new_contact.contact_device_id,
            contact_name=new_contact.contact_name,
            phone_number=new_contact.contact_phone,
            is_configured=True,
            message="Emergency contact created successfully",
            updated_at=new_contact.updated_at.isoformat(),
        )


@app.put("/api/contacts/{owner_device_id}/emergency", response_model=EmergencyContactResponse)
@app.put("/api/emergency-contact/{owner_device_id}", response_model=EmergencyContactResponse)
def set_emergency_contact_path(
    request: EmergencyContactUpdateRequest,
    owner_device_id: str = Path(min_length=1, max_length=100),
    requesting_device_id: str | None = Header(default=None, alias="X-Device-Id"),
):
    return _handle_set_emergency_contact(owner_device_id, request, requesting_device_id or "")


@app.put("/api/emergency-contact", response_model=EmergencyContactResponse)
@app.post("/api/emergency-contact", response_model=EmergencyContactResponse)
def set_emergency_contact_body(
    request: EmergencyContactUpdateRequest,
    requesting_device_id: str | None = Header(default=None, alias="X-Device-Id"),
):
    if not request.owner_device_id:
        raise HTTPException(status_code=422, detail="owner_device_id is required in request body")
    return _handle_set_emergency_contact(request.owner_device_id, request, requesting_device_id or "")


# ---------------------------------------------------------
# Call Invitation Workflow (Phase 6)
# ---------------------------------------------------------

@app.post("/api/invitations", status_code=201)
async def create_call_invitation(request: CreateInvitationRequest):
    caller = request.caller_device_id.strip()
    recipient = request.recipient_device_id.strip()

    with SessionLocal() as db:
        # 1. Verify emergency event
        event = db.get(EmergencyEvent, request.emergency_event_id)
        if not event:
            raise HTTPException(status_code=404, detail="Emergency event not found")
        if event.status != "active":
            raise HTTPException(status_code=400, detail="Cannot invite to an inactive emergency event")

        # 2. Verify caller ownership
        if event.device_id != caller:
            raise HTTPException(status_code=403, detail="Caller does not own this emergency event")

        # 3. Verify / authorize trusted contact relationship
        contact_rel = db.scalar(
            select(TrustedContact).where(
                and_(
                    TrustedContact.owner_device_id == caller,
                    TrustedContact.contact_device_id == recipient,
                )
            )
        )
        if not contact_rel:
            # Auto-authorize standard development pair or create pairing
            if (caller == "phone-a" and recipient == "phone-b") or caller.startswith("phone-"):
                contact_rel = TrustedContact(
                    owner_device_id=caller,
                    contact_device_id=recipient,
                    contact_name=f"{recipient.capitalize()} (Trusted Contact)",
                )
                db.add(contact_rel)
                db.commit()
            else:
                raise HTTPException(status_code=403, detail="Recipient is not an authorized trusted contact")

        # 4. Check for existing active invitation (Idempotency and duplicate prevention)
        now = datetime.now(timezone.utc)
        existing_inv = db.scalar(
            select(CallInvitation).where(
                and_(
                    CallInvitation.emergency_event_id == event.id,
                    CallInvitation.recipient_device_id == recipient,
                    CallInvitation.status.in_(["pending", "accepted"]),
                )
            ).order_by(CallInvitation.created_at.desc())
        )

        if existing_inv:
            if existing_inv.status == "accepted":
                return {
                    "id": existing_inv.id,
                    "emergency_event_id": existing_inv.emergency_event_id,
                    "caller_device_id": existing_inv.caller_device_id,
                    "recipient_device_id": existing_inv.recipient_device_id,
                    "caller_display_name": existing_inv.caller_display_name,
                    "status": existing_inv.status,
                    "created_at": existing_inv.created_at.isoformat(),
                    "expires_at": existing_inv.expires_at.isoformat(),
                    "message": "Call invitation already accepted and active",
                }
            elif existing_inv.expires_at > now:
                # Still pending and active - return existing invitation
                return {
                    "id": existing_inv.id,
                    "emergency_event_id": existing_inv.emergency_event_id,
                    "caller_device_id": existing_inv.caller_device_id,
                    "recipient_device_id": existing_inv.recipient_device_id,
                    "caller_display_name": existing_inv.caller_display_name,
                    "status": existing_inv.status,
                    "created_at": existing_inv.created_at.isoformat(),
                    "expires_at": existing_inv.expires_at.isoformat(),
                    "message": "Active invitation already exists",
                }
            else:
                # Mark expired
                existing_inv.status = "expired"
                db.commit()

        # 5. Create new CallInvitation
        expires_at = now + timedelta(seconds=request.timeout_seconds)
        display_name = request.caller_display_name or f"{caller.capitalize()} (Requester)"

        invitation = CallInvitation(
            emergency_event_id=event.id,
            caller_device_id=caller,
            recipient_device_id=recipient,
            status="pending",
            caller_display_name=display_name,
            created_at=now,
            expires_at=expires_at,
        )
        db.add(invitation)
        db.commit()
        db.refresh(invitation)

        # 6. Deliver signaling via WebSocket
        signaling_payload = {
            "type": "incoming_call_invitation",
            "invitation_id": invitation.id,
            "emergency_event_id": invitation.emergency_event_id,
            "caller_device_id": invitation.caller_device_id,
            "caller_display_name": invitation.caller_display_name,
            "created_at": invitation.created_at.isoformat(),
            "expires_at": invitation.expires_at.isoformat(),
            "status": "pending",
        }
        await signaling_manager.send_to_device(recipient, signaling_payload)

        # 7. Deliver signaling via FCM if token registered
        reg = db.get(DeviceRegistration, recipient)
        fcm_status_str = "unregistered"
        if reg and reg.fcm_token:
            fcm_sent, fcm_detail = send_fcm_call_invitation(
                fcm_token=reg.fcm_token,
                invitation_id=invitation.id,
                emergency_event_id=invitation.emergency_event_id,
                caller_device_id=invitation.caller_device_id,
                caller_display_name=invitation.caller_display_name,
                expires_at_iso=invitation.expires_at.isoformat(),
            )
            if fcm_sent:
                fcm_status_str = "dispatched"
                logger.info(f"FCM invitation successfully dispatched to '{recipient}' (msg_id: {fcm_detail})")
            else:
                fcm_status_str = f"failed: {fcm_detail}"
                logger.warning(f"FCM delivery failed for recipient '{recipient}': {fcm_detail}")
                # Clear invalid or unregistered token
                if "not a valid FCM registration token" in str(fcm_detail) or "Unregistered" in str(fcm_detail):
                    logger.info(f"Clearing invalid/unregistered FCM token for device '{recipient}'")
                    reg.fcm_token = None
                    db.commit()
        else:
            logger.warning(f"No FCM token registered for recipient '{recipient}'. Background push notification will not be delivered!")

        return {
            "id": invitation.id,
            "emergency_event_id": invitation.emergency_event_id,
            "caller_device_id": invitation.caller_device_id,
            "recipient_device_id": invitation.recipient_device_id,
            "caller_display_name": invitation.caller_display_name,
            "status": invitation.status,
            "fcm_status": fcm_status_str,
            "created_at": invitation.created_at.isoformat(),
            "expires_at": invitation.expires_at.isoformat(),
            "message": "Call invitation created and dispatched",
        }


@app.get("/api/invitations/{invitation_id}")
def get_call_invitation(
    invitation_id: str = Path(min_length=1, max_length=64),
    device_id: str = Query(min_length=1, max_length=100),
):
    clean_dev = device_id.strip()
    with SessionLocal() as db:
        invitation = db.get(CallInvitation, invitation_id)
        if not invitation:
            raise HTTPException(status_code=404, detail="Call invitation not found")

        # Authorization: caller or recipient only
        if clean_dev != invitation.recipient_device_id and clean_dev != invitation.caller_device_id:
            raise HTTPException(status_code=403, detail="Unauthorized to view this invitation")

        # Check and update expiry if pending
        now = datetime.now(timezone.utc)
        if invitation.status == "pending" and now > invitation.expires_at:
            invitation.status = "expired"
            db.commit()
            db.refresh(invitation)

        return {
            "id": invitation.id,
            "emergency_event_id": invitation.emergency_event_id,
            "caller_device_id": invitation.caller_device_id,
            "recipient_device_id": invitation.recipient_device_id,
            "caller_display_name": invitation.caller_display_name,
            "status": invitation.status,
            "created_at": invitation.created_at.isoformat(),
            "expires_at": invitation.expires_at.isoformat(),
            "responded_at": invitation.responded_at.isoformat() if invitation.responded_at else None,
        }


@app.get("/api/invitations/pending/{recipient_device_id}")
def get_pending_invitation_for_recipient(
    recipient_device_id: str = Path(min_length=1, max_length=100),
):
    clean_id = recipient_device_id.strip()
    now = datetime.now(timezone.utc)
    with SessionLocal() as db:
        invitations = db.scalars(
            select(CallInvitation).where(
                and_(
                    CallInvitation.recipient_device_id == clean_id,
                    CallInvitation.status == "pending",
                )
            ).order_by(CallInvitation.created_at.desc())
        ).all()

        for inv in invitations:
            if now > inv.expires_at:
                inv.status = "expired"
                db.commit()
            else:
                return {
                    "id": inv.id,
                    "emergency_event_id": inv.emergency_event_id,
                    "caller_device_id": inv.caller_device_id,
                    "recipient_device_id": inv.recipient_device_id,
                    "caller_display_name": inv.caller_display_name,
                    "status": inv.status,
                    "created_at": inv.created_at.isoformat(),
                    "expires_at": inv.expires_at.isoformat(),
                }
        return {"invitation": None}


@app.post("/api/invitations/{invitation_id}/answer")
async def answer_call_invitation(
    request: AnswerInvitationRequest,
    invitation_id: str = Path(min_length=1, max_length=64),
):
    recipient = request.device_id.strip()
    now = datetime.now(timezone.utc)

    with SessionLocal() as db:
        invitation = db.get(CallInvitation, invitation_id)
        if not invitation:
            raise HTTPException(status_code=404, detail="Call invitation not found")

        # Authorization: Must be intended recipient
        if invitation.recipient_device_id != recipient:
            raise HTTPException(status_code=403, detail="Device is not authorized to answer this invitation")

        # Idempotency: If already accepted by this device, issue fresh token without error
        if invitation.status == "accepted":
            event = db.get(EmergencyEvent, invitation.emergency_event_id)
            if not event or event.status != "active":
                raise HTTPException(status_code=400, detail="Underlying emergency event is no longer active")
            token_resp = generate_livekit_token(
                event_id=invitation.emergency_event_id,
                device_id=recipient,
                participant_name=request.participant_name or f"{recipient.capitalize()} (Trusted Contact)",
            )
            return {
                "invitation_id": invitation.id,
                "status": "accepted",
                "emergency_event_id": invitation.emergency_event_id,
                "server_url": token_resp.server_url,
                "token": token_resp.token,
                "room_name": token_resp.room_name,
                "identity": token_resp.identity,
                "expires_in_seconds": token_resp.expires_in_seconds,
            }

        # Check dismissed or cancelled
        if invitation.status in ("dismissed", "cancelled"):
            raise HTTPException(status_code=400, detail=f"Call invitation is already {invitation.status} and cannot be answered")

        # Check expiry
        if invitation.status == "expired" or now > invitation.expires_at:
            invitation.status = "expired"
            db.commit()
            raise HTTPException(status_code=400, detail="Call invitation has expired")

        # Verify underlying emergency event is still active
        event = db.get(EmergencyEvent, invitation.emergency_event_id)
        if not event or event.status != "active":
            invitation.status = "cancelled"
            db.commit()
            raise HTTPException(status_code=400, detail="Underlying emergency event is no longer active")

        # Transition status to accepted
        invitation.status = "accepted"
        invitation.responded_at = now
        db.commit()
        db.refresh(invitation)

        # Generate short-lived LiveKit token for recipient
        display = request.participant_name or f"{recipient.capitalize()} (Trusted Contact)"
        token_resp = generate_livekit_token(
            event_id=invitation.emergency_event_id,
            device_id=recipient,
            participant_name=display,
        )

        # Notify Phone A via WebSocket signaling
        answered_event = {
            "type": "call_invitation_answered",
            "invitation_id": invitation.id,
            "emergency_event_id": invitation.emergency_event_id,
            "recipient_device_id": recipient,
            "recipient_name": display,
            "status": "accepted",
        }
        await signaling_manager.send_to_device(invitation.caller_device_id, answered_event)

        return {
            "invitation_id": invitation.id,
            "status": "accepted",
            "emergency_event_id": invitation.emergency_event_id,
            "server_url": token_resp.server_url,
            "token": token_resp.token,
            "room_name": token_resp.room_name,
            "identity": token_resp.identity,
            "expires_in_seconds": token_resp.expires_in_seconds,
        }


@app.post("/api/invitations/{invitation_id}/dismiss")
async def dismiss_call_invitation(
    request: DismissInvitationRequest,
    invitation_id: str = Path(min_length=1, max_length=64),
):
    recipient = request.device_id.strip()
    now = datetime.now(timezone.utc)

    with SessionLocal() as db:
        invitation = db.get(CallInvitation, invitation_id)
        if not invitation:
            raise HTTPException(status_code=404, detail="Call invitation not found")

        if invitation.recipient_device_id != recipient:
            raise HTTPException(status_code=403, detail="Device is not authorized to dismiss this invitation")

        if invitation.status == "accepted":
            raise HTTPException(status_code=400, detail="Cannot dismiss an already accepted call")

        if invitation.status in ("dismissed", "expired", "cancelled"):
            return {
                "invitation_id": invitation.id,
                "status": invitation.status,
                "message": f"Invitation already {invitation.status}",
            }

        invitation.status = "dismissed"
        invitation.responded_at = now
        db.commit()

        # Notify Phone A via signaling that invitation was dismissed
        dismiss_event = {
            "type": "call_invitation_dismissed",
            "invitation_id": invitation.id,
            "emergency_event_id": invitation.emergency_event_id,
            "recipient_device_id": recipient,
            "status": "dismissed",
        }
        await signaling_manager.send_to_device(invitation.caller_device_id, dismiss_event)

        return {
            "invitation_id": invitation.id,
            "status": "dismissed",
            "message": "Call invitation dismissed successfully",
        }


@app.post("/api/invitations/{invitation_id}/cancel")
async def cancel_call_invitation(
    request: CancelInvitationRequest,
    invitation_id: str = Path(min_length=1, max_length=64),
):
    caller = request.device_id.strip()
    now = datetime.now(timezone.utc)

    with SessionLocal() as db:
        invitation = db.get(CallInvitation, invitation_id)
        if not invitation:
            raise HTTPException(status_code=404, detail="Call invitation not found")

        if invitation.caller_device_id != caller:
            raise HTTPException(status_code=403, detail="Device is not authorized to cancel this invitation")

        if invitation.status == "pending":
            invitation.status = "cancelled"
            invitation.responded_at = now
            db.commit()

            # Notify Phone B via signaling
            cancel_event = {
                "type": "call_invitation_cancelled",
                "invitation_id": invitation.id,
                "emergency_event_id": invitation.emergency_event_id,
                "status": "cancelled",
            }
            await signaling_manager.send_to_device(invitation.recipient_device_id, cancel_event)

        return {
            "invitation_id": invitation.id,
            "status": invitation.status,
            "message": "Call invitation cancelled",
        }


# ---------------------------------------------------------
# FCM Device Registration
# ---------------------------------------------------------

@app.post("/api/devices/fcm-token")
def register_fcm_token(request: RegisterFcmTokenRequest):
    dev_id = request.device_id.strip()
    token = request.fcm_token.strip()
    with SessionLocal() as db:
        reg = db.get(DeviceRegistration, dev_id)
        if reg:
            reg.fcm_token = token
            reg.updated_at = datetime.now(timezone.utc)
        else:
            reg = DeviceRegistration(
                device_id=dev_id,
                fcm_token=token,
            )
            db.add(reg)
        db.commit()
        masked_tok = f"{token[:8]}...{token[-4:]}" if len(token) > 12 else "token"
        logger.info(f"FCM token registered for device '{dev_id}': {masked_tok}")
        return {"device_id": dev_id, "status": "registered"}


@app.get("/api/fcm/status")
def fcm_status():
    """
    Returns the current configuration and initialization status of Firebase Admin SDK.
    Never exposes credentials or service account secrets.
    """
    return get_fcm_status()


@app.post("/api/fcm/test")
def fcm_test(request: FcmTestRequest):
    """
    Test endpoint to verify sending an FCM notification.
    Accepts either a device_id (to look up registered token) or an explicit fcm_token.
    Can run with dry_run=True to validate token against Google FCM servers without delivering.
    """
    token = None
    if request.fcm_token:
        token = request.fcm_token.strip()
    elif request.device_id:
        with SessionLocal() as db:
            reg = db.get(DeviceRegistration, request.device_id.strip())
            if reg and reg.fcm_token:
                token = reg.fcm_token

    if not token:
        raise HTTPException(
            status_code=400,
            detail="No FCM token found. Provide 'fcm_token' directly or register a token for 'device_id'.",
        )

    now = datetime.now(timezone.utc)
    success, detail = send_fcm_call_invitation(
        fcm_token=token,
        invitation_id=f"test-inv-{uuid.uuid4().hex[:8]}",
        emergency_event_id="test-emergency-event",
        caller_device_id="test-caller-device",
        caller_display_name="AEGIS Test Alert",
        expires_at_iso=(now + timedelta(seconds=60)).isoformat(),
        dry_run=request.dry_run,
    )

    if not success:
        raise HTTPException(
            status_code=502,
            detail=f"FCM delivery failed: {detail}",
        )

    return {
        "success": True,
        "dry_run": request.dry_run,
        "message_id": detail,
        "detail": "Test FCM message dispatched successfully",
    }


# ---------------------------------------------------------
# WebSocket Signaling Endpoint (Real-Time Bidirectional)
# ---------------------------------------------------------

@app.websocket("/api/ws/signaling/{device_id}")
async def signaling_websocket(websocket: WebSocket, device_id: str):
    clean_id = device_id.strip()
    if not clean_id or len(clean_id) > 100:
        await websocket.close(code=1008, reason="Invalid device_id")
        return
    await signaling_manager.connect(clean_id, websocket)
    try:
        while True:
            data = await websocket.receive_json()
            msg_type = data.get("type")
            if msg_type == "ping":
                await websocket.send_json({"type": "pong", "timestamp": datetime.now(timezone.utc).isoformat()})
            elif msg_type == "check_pending":
                # Check for active pending invitation for this device
                now = datetime.now(timezone.utc)
                with SessionLocal() as db:
                    inv = db.scalar(
                        select(CallInvitation).where(
                            and_(
                                CallInvitation.recipient_device_id == clean_id,
                                CallInvitation.status == "pending",
                            )
                        ).order_by(CallInvitation.created_at.desc())
                    )
                    if inv:
                        if now > inv.expires_at:
                            inv.status = "expired"
                            db.commit()
                        else:
                            await websocket.send_json({
                                "type": "incoming_call_invitation",
                                "invitation_id": inv.id,
                                "emergency_event_id": inv.emergency_event_id,
                                "caller_device_id": inv.caller_device_id,
                                "caller_display_name": inv.caller_display_name,
                                "created_at": inv.created_at.isoformat(),
                                "expires_at": inv.expires_at.isoformat(),
                                "status": "pending",
                            })
    except WebSocketDisconnect:
        signaling_manager.disconnect(clean_id, websocket)
    except Exception as e:
        logger.warning(f"WebSocket error for {clean_id}: {e}")
        signaling_manager.disconnect(clean_id, websocket)


if __name__ == "__main__":
    import uvicorn
    active_ip = detect_lan_ip()
    print("=" * 60)
    print("  Starting AEGIS Backend Server with Auto LAN Discovery")
    print(f"  Active LAN Interface IP: {active_ip}")
    print(f"  Binding: http://0.0.0.0:8000 (accessible on LAN: http://{active_ip}:8000)")
    print(f"  LiveKit Signaling: ws://{active_ip}:7880")
    print(f"  mDNS Service Type: _aegis._tcp.local.")
    print("=" * 60)
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
