"""
Batch calibration upload + background fine-tune.

POST /v1/calibrate — upload all clips at once and queue local training.
"""
from __future__ import annotations

import json
import logging
import os
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, UploadFile, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_current_user_optional, get_db
from app.models.db_models import CalibrationSampleRecord, CalibrationSessionRecord, UserRecord
from app.models.pydantic_models import CalibrateResponse
from app.services.session_status import write_status
from app.storage.local_storage import get_sample_path, save_upload
from app.workers.train_worker import create_job_record, run_training_job

logger = logging.getLogger(__name__)
router = APIRouter(tags=["calibrate"])


def _extract_manifest_map(raw_content: str | bytes | dict) -> dict[str, str]:
    """Parse manifest JSON into a flat filename -> prompt_text mapping."""
    data = json.loads(raw_content) if isinstance(raw_content, (str, bytes)) else raw_content
    mapping: dict[str, str] = {}

    if isinstance(data, dict):
        if "mapping" in data and isinstance(data["mapping"], dict):
            for k, v in data["mapping"].items():
                mapping[str(k)] = str(v)
        for k, v in data.items():
            if k in ("session_id", "mapping"):
                continue
            if isinstance(v, str):
                mapping[str(k)] = v
            elif isinstance(v, dict) and "prompt_text" in v:
                mapping[str(k)] = str(v["prompt_text"])
    elif isinstance(data, list):
        for item in data:
            if isinstance(item, dict):
                fn = item.get("filename") or item.get("file_name")
                txt = item.get("prompt_text") or item.get("text")
                if fn and txt:
                    mapping[str(fn)] = str(txt)

    # Normalize keys so both "phrase_01" and "phrase_01.wav" match
    normalized: dict[str, str] = dict(mapping)
    for k, v in mapping.items():
        if k.endswith(".wav"):
            normalized[k[:-4]] = v
        else:
            normalized[f"{k}.wav"] = v
    return normalized


@router.post(
    "/calibrate",
    response_model=CalibrateResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Upload calibration clips + manifest and queue local fine-tune",
)
async def calibrate(
    background_tasks: BackgroundTasks,
    session_id: str = Form(...),
    files: list[UploadFile] = File(...),
    manifest: UploadFile | None = File(None),
    manifest_json: str | None = Form(None),
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord | None = Depends(get_current_user_optional),
) -> CalibrateResponse:
    if not settings.LIVE_TRAINING_ENABLED:
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail={
                "code": "LIVE_TRAINING_DISABLED",
                "message": "Set LIVE_TRAINING_ENABLED=true and install ml/requirements-training.txt",
            },
        )

    # Resolve or auto-create calibration session
    result = await db.execute(
        select(CalibrationSessionRecord).where(CalibrationSessionRecord.session_id == session_id)
    )
    cal_session = result.scalar_one_or_none()
    effective_user_id = current_user.user_id if current_user else "local_user"

    if cal_session is None:
        cal_session = CalibrationSessionRecord(
            session_id=session_id,
            user_id=effective_user_id,
            prompt_set_id="torgo_en_v1",
            status="COLLECTING",
            samples_received=0,
            samples_required=len(files),
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
        )
        db.add(cal_session)
        await db.flush()
    elif current_user and cal_session.user_id != current_user.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Session belongs to another user")

    # Directory for session files: ./sessions/{session_id}/
    batch_dir = settings.SESSIONS_DIR / session_id
    os.makedirs(batch_dir, exist_ok=True)

    manifest_map: dict[str, str] = {}

    # 1. Parse manifest from form field if provided
    if manifest_json:
        try:
            manifest_map.update(_extract_manifest_map(manifest_json))
            with open(batch_dir / "manifest.json", "w", encoding="utf-8") as out:
                out.write(manifest_json)
        except Exception as e:
            logger.warning("Could not parse manifest_json form field: %s", e)

    # 2. Parse manifest from dedicated manifest file if provided
    if manifest and manifest.filename:
        try:
            m_bytes = await manifest.read()
            manifest_map.update(_extract_manifest_map(m_bytes))
            with open(batch_dir / "manifest.json", "wb") as out:
                out.write(m_bytes)
        except Exception as e:
            logger.warning("Could not parse uploaded manifest file: %s", e)

    audio_files: list[UploadFile] = []
    for f in files:
        if not f.filename:
            continue
        # Check if manifest.json was passed inside files list
        if f.filename.lower() == "manifest.json":
            try:
                m_bytes = await f.read()
                manifest_map.update(_extract_manifest_map(m_bytes))
                with open(batch_dir / "manifest.json", "wb") as out:
                    out.write(m_bytes)
            except Exception as e:
                logger.warning("Could not parse manifest.json from files: %s", e)
            continue
        audio_files.append(f)

    if not audio_files:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No audio files provided")

    for f in audio_files:
        raw_bytes = await f.read()
        if len(raw_bytes) < 500:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Audio file '{f.filename}' is too small or corrupt",
            )
        # Save raw copy in ./sessions/{session_id}/{filename}
        with open(batch_dir / f.filename, "wb") as out:
            out.write(raw_bytes)

        sample_id = str(uuid.uuid4())
        suffix = "." + f.filename.rsplit(".", 1)[-1] if "." in f.filename else ".wav"
        dest = get_sample_path(session_id, sample_id, suffix)
        await save_upload(dest, raw_bytes)

        # Ground-truth prompt from manifest mapping (crucial for training)
        prompt_text = (
            manifest_map.get(f.filename)
            or manifest_map.get(f.filename.rsplit(".", 1)[0])
            or f.filename.rsplit(".", 1)[0]
        )

        sample = CalibrationSampleRecord(
            sample_id=sample_id,
            session_id=session_id,
            prompt_text=prompt_text,
            audio_storage_path=str(dest),
            duration_ms=int(len(raw_bytes) / (16000 * 2) * 1000),
            uploaded_at=datetime.now(timezone.utc),
        )
        db.add(sample)
        cal_session.samples_received += 1

    cal_session.status = "COLLECTING"
    cal_session.updated_at = datetime.now(timezone.utc)
    await db.commit()

    # Save finalized manifest in session directory if not already written
    if not (batch_dir / "manifest.json").exists() and manifest_map:
        (batch_dir / "manifest.json").write_text(json.dumps(manifest_map, indent=2), encoding="utf-8")

    job_id = create_job_record(session_id, cal_session.user_id)
    write_status(session_id, status="queued", job_id=job_id, message="Training queued")

    # Background non-blocking execution
    background_tasks.add_task(run_training_job, job_id, session_id, cal_session.user_id)

    return CalibrateResponse(status="queued", session_id=session_id, job_id=job_id)

