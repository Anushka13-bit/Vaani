"""
VaaniMitra — Calibration Router (§5.2)
GET  /v1/calibration/prompts
POST /v1/calibration/sessions
POST /v1/calibration/sessions/{session_id}/samples
POST /v1/calibration/sessions/{session_id}/train   ← STUBBED (501)
GET  /v1/calibration/sessions/{session_id}/status
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, Query, UploadFile, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_current_user, get_db
from app.models.db_models import (
    CalibrationSampleRecord,
    CalibrationSessionRecord,
    PromptRecord,
    TrainingJobRecord,
    UserRecord,
)
from app.models.pydantic_models import (
    CreateSessionRequest,
    CreateSessionResponse,
    PromptSetResponse,
    Prompt,
    SampleUploadResponse,
    SessionStatusResponse,
    TrainResponse,
)
from app.services.session_status import read_status
from app.storage.local_storage import get_sample_path, save_upload
from app.db.init_db import ENGLISH_PROMPT_SET_ID
from app.workers.train_worker import create_job_record, run_training_job

router = APIRouter(prefix="/calibration", tags=["calibration"])


# ── GET prompts ───────────────────────────────────────────────────────────────

@router.get(
    "/prompts",
    response_model=PromptSetResponse,
    summary="Get calibration prompts for a language",
)
async def get_prompts(
    language: str = Query(default="en", description="BCP-47 language code, e.g. en, ta"),
    count: int = Query(default=5, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
) -> PromptSetResponse:
    result = await db.execute(
        select(PromptRecord)
        .where(PromptRecord.language == language)
        .limit(count)
    )
    prompts = result.scalars().all()

    if not prompts:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No calibration prompts found for language='{language}'. "
                   "Check that seed data has been loaded (restart the server).",
        )

    # Infer prompt_set_id from the first prompt record
    prompt_set_id = prompts[0].prompt_set_id

    return PromptSetResponse(
        prompt_set_id=prompt_set_id,
        prompts=[Prompt(prompt_id=p.prompt_id, text=p.text, language=p.language) for p in prompts],
    )


# ── POST sessions ─────────────────────────────────────────────────────────────

@router.post(
    "/sessions",
    response_model=CreateSessionResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a calibration session",
)
async def create_session(
    body: CreateSessionRequest,
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> CreateSessionResponse:
    # Check user exists and matches token
    if current_user.user_id != body.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_id mismatch")

    # Reject if there's already an active (non-failed) session for this user
    result = await db.execute(
        select(CalibrationSessionRecord)
        .where(
            CalibrationSessionRecord.user_id == body.user_id,
            CalibrationSessionRecord.status.not_in(["COMPLETE", "FAILED"]),
        )
        .limit(1)
    )
    existing_session = result.scalar_one_or_none()
    if existing_session:
        return CreateSessionResponse(
            session_id=existing_session.session_id,
            status=existing_session.status,
            samples_required=existing_session.samples_required,
        )

    session = CalibrationSessionRecord(
        session_id=str(uuid.uuid4()),
        user_id=body.user_id,
        prompt_set_id=body.prompt_set_id,
        status="CREATED",
        samples_received=0,
        samples_required=settings.DEFAULT_SAMPLE_COUNT,
        created_at=datetime.now(timezone.utc),
        updated_at=datetime.now(timezone.utc),
    )
    db.add(session)
    await db.flush()

    return CreateSessionResponse(
        session_id=session.session_id,
        status=session.status,
        samples_required=session.samples_required,
    )


# ── POST samples ──────────────────────────────────────────────────────────────

@router.post(
    "/sessions/{session_id}/samples",
    response_model=SampleUploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload a single calibration audio sample",
)
async def upload_sample(
    session_id: str,
    audio: UploadFile = File(..., description="WAV or M4A audio file, 16kHz mono recommended"),
    prompt_id: str = Form(...),
    db: AsyncSession = Depends(get_db),
    _: UserRecord = Depends(get_current_user),
) -> SampleUploadResponse:
    result = await db.execute(
        select(CalibrationSessionRecord).where(CalibrationSessionRecord.session_id == session_id)
    )
    cal_session = result.scalar_one_or_none()
    if cal_session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    if cal_session.status not in ("CREATED", "COLLECTING"):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT,
                            detail=f"Session is in state '{cal_session.status}', cannot accept samples")

    # Fetch prompt text
    prompt_result = await db.execute(
        select(PromptRecord).where(PromptRecord.prompt_id == prompt_id)
    )
    prompt = prompt_result.scalar_one_or_none()
    prompt_text = prompt.text if prompt else f"[prompt {prompt_id}]"

    # Save audio
    sample_id = str(uuid.uuid4())
    suffix = "." + (audio.filename.rsplit(".", 1)[-1] if audio.filename and "." in audio.filename else "wav")
    dest = get_sample_path(session_id, sample_id, suffix)
    audio_bytes = await audio.read()
    await save_upload(dest, audio_bytes)

    # Duration estimation (bytes / (16000 * 2) * 1000 ms for 16kHz mono 16-bit)
    duration_ms = int(len(audio_bytes) / (16000 * 2) * 1000)

    sample = CalibrationSampleRecord(
        sample_id=sample_id,
        session_id=session_id,
        prompt_text=prompt_text,
        audio_storage_path=str(dest),
        duration_ms=duration_ms,
        uploaded_at=datetime.now(timezone.utc),
    )
    db.add(sample)

    cal_session.samples_received += 1
    if cal_session.status == "CREATED":
        cal_session.status = "COLLECTING"
    cal_session.updated_at = datetime.now(timezone.utc)

    await db.flush()

    return SampleUploadResponse(
        sample_id=sample_id,
        samples_received=cal_session.samples_received,
        samples_required=cal_session.samples_required,
    )


# ── POST train (STUBBED) ──────────────────────────────────────────────────────

@router.post(
    "/sessions/{session_id}/train",
    response_model=TrainResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Trigger local LoRA fine-tune + ONNX export (background task)",
)
async def trigger_training(
    session_id: str,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> TrainResponse:
    if not settings.LIVE_TRAINING_ENABLED:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail={
                "code": "LIVE_TRAINING_DISABLED",
                "message": (
                    "Set LIVE_TRAINING_ENABLED=true and install ml/requirements-training.txt. "
                    "Fallback: GET /v1/adapters/clusters for pre-baked cluster adapter."
                ),
            },
        )

    result = await db.execute(
        select(CalibrationSessionRecord).where(CalibrationSessionRecord.session_id == session_id)
    )
    cal_session = result.scalar_one_or_none()
    if cal_session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    if cal_session.user_id != current_user.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Forbidden")
    if cal_session.samples_received < 3:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Need at least 3 samples; have {cal_session.samples_received}",
        )

    job_id = create_job_record(session_id, cal_session.user_id)
    from app.services.session_status import write_status

    write_status(session_id, status="queued", job_id=job_id, message="Training queued")
    background_tasks.add_task(run_training_job, job_id, session_id, cal_session.user_id)

    return TrainResponse(job_id=job_id, status="QUEUED")


# ── GET status ────────────────────────────────────────────────────────────────

@router.get(
    "/sessions/{session_id}/status",
    response_model=SessionStatusResponse,
    summary="Poll calibration session status",
)
async def get_session_status(
    session_id: str,
    db: AsyncSession = Depends(get_db),
    _: UserRecord = Depends(get_current_user),
) -> SessionStatusResponse:
    result = await db.execute(
        select(CalibrationSessionRecord).where(CalibrationSessionRecord.session_id == session_id)
    )
    cal_session = result.scalar_one_or_none()
    if cal_session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")

    # Resolve latest job if any
    job_result = await db.execute(
        select(TrainingJobRecord)
        .where(TrainingJobRecord.session_id == session_id)
        .order_by(TrainingJobRecord.started_at.desc().nullslast())
        .limit(1)
    )
    job = job_result.scalar_one_or_none()

    # Prefer fine-grained status.json when available
    file_status = read_status(session_id)
    if file_status:
        phase = file_status.get("status", "queued")
        progress_map = {"queued": 10, "training": 45, "exporting": 80, "ready": 100, "failed": 0}
        db_status = cal_session.status
        if phase == "ready":
            db_status = "COMPLETE"
        elif phase == "failed":
            db_status = "FAILED"
        elif phase in ("training", "exporting"):
            db_status = "TRAINING"
        return SessionStatusResponse(
            status=db_status,  # type: ignore[arg-type]
            job_id=file_status.get("job_id") or (job.job_id if job else None),
            progress_pct=progress_map.get(phase, 0),
            resulting_adapter_id=file_status.get("adapter_id") or (job.resulting_adapter_id if job else None),
        )

    progress = 0
    if cal_session.status == "TRAINING":
        progress = 50
    elif cal_session.status == "COMPLETE":
        progress = 100

    return SessionStatusResponse(
        status=cal_session.status,  # type: ignore[arg-type]
        job_id=job.job_id if job else None,
        progress_pct=progress,
        resulting_adapter_id=job.resulting_adapter_id if job else None,
    )
