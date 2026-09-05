"""
VaaniMitra — SQLAlchemy ORM Models (native DB schema)
Maps to the Pydantic models in pydantic_models.py.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    Boolean, Column, DateTime, Float, ForeignKey,
    Integer, String, Text
)
from sqlalchemy.orm import DeclarativeBase, relationship


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _uuid() -> str:
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


# ── Users ─────────────────────────────────────────────────────────────────────

class UserRecord(Base):
    __tablename__ = "users"

    user_id = Column(String, primary_key=True, default=_uuid)
    device_id = Column(String, unique=True, nullable=False, index=True)
    preferred_language = Column(String, nullable=False, default="en")
    dysarthria_severity_hint = Column(String, nullable=True)
    access_token_hash = Column(String, nullable=True)
    token_expires_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), default=_now, nullable=False)

    calibration_sessions = relationship("CalibrationSessionRecord", back_populates="user")
    corrections = relationship("CorrectionRecord", back_populates="user")
    adapters = relationship("AdapterRecord", back_populates="user")


# ── Calibration ───────────────────────────────────────────────────────────────

class PromptRecord(Base):
    __tablename__ = "prompts"

    prompt_id = Column(String, primary_key=True, default=_uuid)
    prompt_set_id = Column(String, nullable=False, index=True)
    text = Column(Text, nullable=False)
    language = Column(String, nullable=False, default="en")


class CalibrationSessionRecord(Base):
    __tablename__ = "calibration_sessions"

    session_id = Column(String, primary_key=True, default=_uuid)
    user_id = Column(String, ForeignKey("users.user_id"), nullable=False, index=True)
    prompt_set_id = Column(String, nullable=False)
    status = Column(String, nullable=False, default="CREATED")
    samples_received = Column(Integer, default=0, nullable=False)
    samples_required = Column(Integer, default=40, nullable=False)
    created_at = Column(DateTime(timezone=True), default=_now, nullable=False)
    updated_at = Column(DateTime(timezone=True), default=_now, onupdate=_now, nullable=False)

    user = relationship("UserRecord", back_populates="calibration_sessions")
    samples = relationship("CalibrationSampleRecord", back_populates="session")
    training_jobs = relationship("TrainingJobRecord", back_populates="session")


class CalibrationSampleRecord(Base):
    __tablename__ = "calibration_samples"

    sample_id = Column(String, primary_key=True, default=_uuid)
    session_id = Column(String, ForeignKey("calibration_sessions.session_id"), nullable=False, index=True)
    prompt_text = Column(Text, nullable=False)
    audio_storage_path = Column(String, nullable=False)
    duration_ms = Column(Integer, default=0)
    uploaded_at = Column(DateTime(timezone=True), default=_now, nullable=False)

    session = relationship("CalibrationSessionRecord", back_populates="samples")


# ── Adapters ─────────────────────────────────────────────────────────────────

class AdapterRecord(Base):
    __tablename__ = "adapters"

    adapter_id = Column(String, primary_key=True)
    type = Column(String, nullable=False)                # USER | LANGUAGE | CLUSTER
    user_id = Column(String, ForeignKey("users.user_id"), nullable=True, index=True)
    language_code = Column(String, nullable=False, index=True)
    severity_cluster = Column(String, nullable=True)
    base_model = Column(String, nullable=False)
    parent_adapter_id = Column(String, nullable=True)
    version = Column(Integer, nullable=False, default=1)
    storage_path = Column(String, nullable=False)        # relative path under STORAGE_ROOT or ADAPTERS_DIR
    checksum = Column(String, nullable=False, default="")
    training_job_id = Column(String, nullable=True)
    is_active = Column(Boolean, default=True, nullable=False)
    created_at = Column(DateTime(timezone=True), default=_now, nullable=False)

    user = relationship("UserRecord", back_populates="adapters")


# ── Training Jobs ─────────────────────────────────────────────────────────────

class TrainingJobRecord(Base):
    __tablename__ = "training_jobs"

    job_id = Column(String, primary_key=True, default=_uuid)
    session_id = Column(String, ForeignKey("calibration_sessions.session_id"), nullable=True)
    user_id = Column(String, nullable=True)
    trigger = Column(String, nullable=False)             # CALIBRATION | CORRECTION_THRESHOLD | MANUAL
    status = Column(String, nullable=False, default="QUEUED")
    resulting_adapter_id = Column(String, nullable=True)
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    error_message = Column(Text, nullable=True)

    session = relationship("CalibrationSessionRecord", back_populates="training_jobs")


# ── Corrections ───────────────────────────────────────────────────────────────

class CorrectionRecord(Base):
    __tablename__ = "corrections"

    correction_id = Column(String, primary_key=True, default=_uuid)
    user_id = Column(String, ForeignKey("users.user_id"), nullable=False, index=True)
    original_transcript = Column(Text, nullable=False)
    corrected_transcript = Column(Text, nullable=False)
    audio_storage_path = Column(String, nullable=True)
    confidence_at_time = Column(Float, nullable=False, default=0.0)
    synced = Column(Boolean, default=True, nullable=False)
    used_in_retrain = Column(Boolean, default=False, nullable=False)
    received_at = Column(DateTime(timezone=True), default=_now, nullable=False)

    user = relationship("UserRecord", back_populates="corrections")


# ── Transcript Log ────────────────────────────────────────────────────────────

class TranscriptLogRecord(Base):
    __tablename__ = "transcript_log"

    id = Column(String, primary_key=True, default=_uuid)
    user_id = Column(String, nullable=False, index=True)
    transcript = Column(Text, nullable=False)
    confidence = Column(Float, nullable=False, default=0.0)
    source_app = Column(String, nullable=True)
    timestamp = Column(DateTime(timezone=True), default=_now, nullable=False)
    flagged_for_caregiver_review = Column(Boolean, default=False)


# ── Caregiver ─────────────────────────────────────────────────────────────────

class CaregiverLinkRecord(Base):
    __tablename__ = "caregiver_links"

    caregiver_id = Column(String, primary_key=True, default=_uuid)
    user_id = Column(String, ForeignKey("users.user_id"), nullable=False, index=True)
    caregiver_email = Column(String, nullable=False)
    relationship_label = Column(String, nullable=True)
    permissions = Column(String, nullable=False, default="VIEW_TRANSCRIPTS")  # comma-separated
    status = Column(String, nullable=False, default="PENDING_USER_APPROVAL")
    linked_at = Column(DateTime(timezone=True), default=_now, nullable=False)

    # Caregiver-side phrasebook edits
    phrasebook_entries = relationship("CaregiverPhrasebookRecord", back_populates="link")


class CaregiverPhrasebookRecord(Base):
    __tablename__ = "caregiver_phrasebook"

    entry_id = Column(String, primary_key=True, default=_uuid)
    caregiver_id = Column(String, ForeignKey("caregiver_links.caregiver_id"), nullable=False)
    user_id = Column(String, nullable=False, index=True)
    trigger_phrase = Column(String, nullable=False)
    action_type = Column(String, nullable=False)
    action_payload_json = Column(Text, nullable=False, default="{}")
    updated_at = Column(DateTime(timezone=True), default=_now, onupdate=_now)

    link = relationship("CaregiverLinkRecord", back_populates="phrasebook_entries")
