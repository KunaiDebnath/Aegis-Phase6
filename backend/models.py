import uuid
from datetime import datetime, timezone

from sqlalchemy import String, DateTime, Float, ForeignKey, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from database import Base


class EmergencyEvent(Base):
    __tablename__ = "emergency_events"

    id: Mapped[str] = mapped_column(
        String(36),
        primary_key=True,
        default=lambda: str(uuid.uuid4()),
    )

    idempotency_key: Mapped[str] = mapped_column(
        String(64),
        unique=True,
        nullable=False,
        index=True,
    )

    device_id: Mapped[str] = mapped_column(
        String(100),
        nullable=False,
    )

    trigger_source: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="manual",
    )

    status: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="active",
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )

    latitude: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
    )

    longitude: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
    )

    location_accuracy_meters: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
    )

    location_timestamp: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )


class TrustedContact(Base):
    __tablename__ = "trusted_contacts"
    __table_args__ = (
        UniqueConstraint("owner_device_id", "contact_device_id", name="uq_owner_contact_device"),
    )

    id: Mapped[str] = mapped_column(
        String(36),
        primary_key=True,
        default=lambda: str(uuid.uuid4()),
    )

    owner_device_id: Mapped[str] = mapped_column(
        String(100),
        nullable=False,
        index=True,
    )

    contact_device_id: Mapped[str] = mapped_column(
        String(100),
        nullable=False,
        index=True,
    )

    contact_name: Mapped[str | None] = mapped_column(
        String(100),
        nullable=True,
    )

    contact_phone: Mapped[str | None] = mapped_column(
        String(50),
        nullable=True,
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )


class CallInvitation(Base):
    __tablename__ = "call_invitations"

    id: Mapped[str] = mapped_column(
        String(36),
        primary_key=True,
        default=lambda: str(uuid.uuid4()),
    )

    emergency_event_id: Mapped[str] = mapped_column(
        String(36),
        ForeignKey("emergency_events.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    caller_device_id: Mapped[str] = mapped_column(
        String(100),
        nullable=False,
        index=True,
    )

    recipient_device_id: Mapped[str] = mapped_column(
        String(100),
        nullable=False,
        index=True,
    )

    status: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="pending",
        index=True,
    )

    caller_display_name: Mapped[str | None] = mapped_column(
        String(100),
        nullable=True,
    )

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )

    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        index=True,
    )

    responded_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
    )


class DeviceRegistration(Base):
    __tablename__ = "device_registrations"

    device_id: Mapped[str] = mapped_column(
        String(100),
        primary_key=True,
    )

    fcm_token: Mapped[str | None] = mapped_column(
        String(1024),
        nullable=True,
    )

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
