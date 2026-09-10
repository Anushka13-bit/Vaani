"""
Batch calibration upload + background fine-tune.

POST /v1/calibrate — upload all clips at once and queue local training.
"""
from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, UploadFile, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_current_user, get_db
from app.models.db_models import CalibrationSampleRecord, CalibrationSessionRecord, UserRecord
from app.models.pydantic_models import CalibrateResponse
from app.services.session_status import write_status
from app.storage.local_storage import get_sample_path, save_upload
from app.workers.train_worker import create_job_record, run_training_job

router = APIRouter(tags=["calibrate"])


@router.post(
    "/calibrate",
    response_model=CalibrateResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Upload calibration clips and queue local fine-tune",
)
async def calibrate(
    background_tasks: BackgroundTasks,
    session_id: str = Form(...),
    files: list[UploadFile] = File(...),
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> CalibrateResponse:
    if not settings.LIVE_TRAINING_ENABLED:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail={
                "code": "LIVE_TRAINING_DISABLED",
                "message": "Set LIVE_TRAINING_ENABLED=true and install ml/requirements-training.txt",
            },
        )

    result = await db.execute(
        select(CalibrationSessionRecord).where(CalibrationSessionRecord.session_id == session_id)
    )
    cal_session = result.scalar_one_or_none()
    if cal_session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    if cal_session.user_id != current_user.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Session belongs to another user")

    # Also save raw copies under ./sessions/{session_id}/ for debugging
    batch_dir = settings.SESSIONS_DIR / session_id
    os.makedirs(batch_dir, exist_ok=True)

    if not files:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No audio files provided")

    for f in files:
        if not f.filename:
            continue
        raw_bytes = await f.read()
        if len(raw_bytes) < 1000:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Audio file '{f.filename}' is too small or corrupt",
            )
        with open(batch_dir / f.filename, "wb") as out:
            out.write(raw_bytes)

        sample_id = str(uuid.uuid4())
        suffix = "." + f.filename.rsplit(".", 1)[-1] if "." in f.filename else ".wav"
        dest = get_sample_path(session_id, sample_id, suffix)
        await save_upload(dest, raw_bytes)

        sample = CalibrationSampleRecord(
            sample_id=sample_id,
            session_id=session_id,
            prompt_text=f.filename.rsplit(".", 1)[0],
            audio_storage_path=str(dest),
            duration_ms=int(len(raw_bytes) / (16000 * 2) * 1000),
            uploaded_at=datetime.now(timezone.utc),
        )
        db.add(sample)
        cal_session.samples_received += 1

    cal_session.status = "COLLECTING"
    cal_session.updated_at = datetime.now(timezone.utc)
    await db.flush()

    job_id = create_job_record(session_id, cal_session.user_id)
    write_status(session_id, status="queued", job_id=job_id, message="Training queued")

    background_tasks.add_task(run_training_job, job_id, session_id, cal_session.user_id)

    return CalibrateResponse(status="queued", session_id=session_id, job_id=job_id)
