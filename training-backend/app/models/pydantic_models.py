"""
VaaniMitra — Pydantic Models (§4.2 + §5 of technical spec)
All request/response schemas for the FastAPI layer.
"""
from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional
from pydantic import BaseModel, Field


# ── Auth ─────────────────────────────────────────────────────────────────────

class DeviceRegisterRequest(BaseModel):
    device_id: str
    preferred_language: str = "en"
    dysarthria_severity_hint: Optional[str] = None  # e.g. "mild" | "moderate" | "severe"


class DeviceRegisterResponse(BaseModel):
    user_id: str
    access_token: str
    expires_at: datetime


# ── Users ─────────────────────────────────────────────────────────────────────

class User(BaseModel):
    user_id: str
    device_id: str
    preferred_language: str
    dysarthria_severity_hint: Optional[str] = None
    created_at: datetime


# ── Calibration ───────────────────────────────────────────────────────────────

class Prompt(BaseModel):
    prompt_id: str
    text: str
    language: str


class PromptSetResponse(BaseModel):
    prompt_set_id: str
    prompts: list[Prompt]


class CreateSessionRequest(BaseModel):
    user_id: str
    prompt_set_id: str


class CreateSessionResponse(BaseModel):
    session_id: str
    status: str
    samples_required: int


class SampleUploadResponse(BaseModel):
    sample_id: str
    samples_received: int
    samples_required: int


class TrainRequest(BaseModel):
    """Body is optional — the backend infers warm-start adapter from user profile."""
    pass


class TrainResponse(BaseModel):
    job_id: str
    status: str


class SessionStatusResponse(BaseModel):
    status: Literal["CREATED", "COLLECTING", "TRAINING", "COMPLETE", "FAILED"]
    job_id: Optional[str] = None
    progress_pct: int = 0
    resulting_adapter_id: Optional[str] = None


class CalibrateResponse(BaseModel):
    status: str
    session_id: str
    job_id: str


class SessionAdapterStatusResponse(BaseModel):
    session_id: str
    status: Literal["queued", "training", "exporting", "ready", "failed"]
    progress_pct: int = 0
    adapter_id: Optional[str] = None
    job_id: Optional[str] = None
    message: Optional[str] = None
    error: Optional[str] = None


# ── Adapters ─────────────────────────────────────────────────────────────────

class AdapterRecordBackend(BaseModel):
    adapter_id: str
    type: Literal["USER", "LANGUAGE", "CLUSTER"]
    user_id: Optional[str] = None
    language_code: str
    severity_cluster: Optional[str] = None
    base_model: str
    parent_adapter_id: Optional[str] = None
    version: int
    storage_path: str
    checksum: str
    training_job_id: Optional[str] = None
    created_at: datetime


class AdapterMetaResponse(BaseModel):
    adapter_id: str
    type: str
    language_code: str
    severity_cluster: Optional[str] = None
    version: int
    checksum: str
    download_url: str


class ClusterAdapterResponse(BaseModel):
    adapter_id: str
    version: int
    download_url: str


class UserAdapterResponse(BaseModel):
    adapter_id: str
    version: int
    updated_at: datetime


# ── Training Jobs ─────────────────────────────────────────────────────────────

class TrainingJob(BaseModel):
    job_id: str
    session_id: Optional[str] = None
    user_id: Optional[str] = None
    trigger: Literal["CALIBRATION", "CORRECTION_THRESHOLD", "MANUAL"]
    status: Literal["QUEUED", "RUNNING", "SUCCEEDED", "FAILED"]
    resulting_adapter_id: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_message: Optional[str] = None


# ── Corrections ───────────────────────────────────────────────────────────────

class CorrectionItem(BaseModel):
    original_transcript: str
    corrected_transcript: str
    confidence_at_time: float = Field(ge=0.0, le=1.0)
    audio_included: bool = False


class CorrectionsUploadRequest(BaseModel):
    user_id: str
    corrections: list[CorrectionItem]


class CorrectionsUploadResponse(BaseModel):
    accepted: int
    retrain_triggered: bool


class Correction(BaseModel):
    correction_id: str
    user_id: str
    original_transcript: str
    corrected_transcript: str
    audio_storage_path: Optional[str] = None
    confidence_at_time: float
    received_at: datetime


# ── Caregiver ─────────────────────────────────────────────────────────────────

class CaregiverLinkRequest(BaseModel):
    user_id: str
    caregiver_email: str
    permissions: list[Literal["VIEW_TRANSCRIPTS", "EDIT_PHRASEBOOK"]]


class CaregiverLinkResponse(BaseModel):
    caregiver_id: str
    status: str


class TranscriptEntry(BaseModel):
    id: str
    transcript: str
    confidence: float
    timestamp: datetime


class TranscriptListResponse(BaseModel):
    transcripts: list[TranscriptEntry]


class PhrasebookUpdateRequest(BaseModel):
    trigger_phrase: str
    action_type: str
    action_payload: dict


class PhrasebookUpdateResponse(BaseModel):
    entry_id: str
    updated: bool


class CaregiverLink(BaseModel):
    caregiver_id: str
    user_id: str
    relationship: Optional[str] = None
    permissions: list[Literal["VIEW_TRANSCRIPTS", "EDIT_PHRASEBOOK"]]
    linked_at: datetime


# ── Error ─────────────────────────────────────────────────────────────────────

class ErrorDetail(BaseModel):
    code: str
    message: str
    request_id: str


class ErrorResponse(BaseModel):
    error: ErrorDetail


# ── Calibration Session internal ──────────────────────────────────────────────

class CalibrationSession(BaseModel):
    session_id: str
    user_id: str
    prompt_set_id: str
    status: Literal["CREATED", "COLLECTING", "TRAINING", "COMPLETE", "FAILED"]
    samples_received: int
    samples_required: int
    created_at: datetime
    updated_at: datetime


class CalibrationSample(BaseModel):
    sample_id: str
    session_id: str
    prompt_text: str
    audio_storage_path: str
    duration_ms: int
    uploaded_at: datetime
