"""
VaaniMitra — Corrections Router (§5.4)
POST /v1/corrections — batch upload user corrections (opt-in only)
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_current_user, get_db
from app.models.db_models import CorrectionRecord, UserRecord
from app.models.pydantic_models import CorrectionsUploadRequest, CorrectionsUploadResponse
from app.storage.local_storage import get_correction_audio_path, save_upload

router = APIRouter(prefix="/corrections", tags=["corrections"])


@router.post(
    "",
    response_model=CorrectionsUploadResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Batch upload corrections for opt-in retraining",
)
async def upload_corrections(
    body: CorrectionsUploadRequest,
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> CorrectionsUploadResponse:
    """
    Accepts user correction records for periodic retraining.
    Caller MUST have explicit user consent before calling this endpoint —
    consent is enforced on the mobile app side (Settings toggle).
    """
    if current_user.user_id != body.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_id mismatch")

    if not body.corrections:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="corrections list is empty")

    now = datetime.now(timezone.utc)
    accepted = 0
    for item in body.corrections:
        record = CorrectionRecord(
            correction_id=str(uuid.uuid4()),
            user_id=body.user_id,
            original_transcript=item.original_transcript,
            corrected_transcript=item.corrected_transcript,
            audio_storage_path=None,  # audio upload handled separately if needed
            confidence_at_time=item.confidence_at_time,
            synced=True,
            used_in_retrain=False,
            received_at=now,
        )
        db.add(record)
        accepted += 1

    await db.flush()

    # Check retrain threshold
    count_result = await db.execute(
        select(func.count()).select_from(CorrectionRecord).where(
            CorrectionRecord.user_id == body.user_id,
            CorrectionRecord.synced.is_(True),
            CorrectionRecord.used_in_retrain.is_(False),
        )
    )
    pending_count = count_result.scalar_one()
    retrain_triggered = pending_count >= settings.CORRECTION_RETRAIN_THRESHOLD

    # STUB: if threshold reached and live training enabled, enqueue job.
    # Live training is disabled — log and skip.
    if retrain_triggered and settings.LIVE_TRAINING_ENABLED:
        # TODO: enqueue TrainingJobRecord with trigger=CORRECTION_THRESHOLD
        pass
    elif retrain_triggered:
        import logging
        logging.getLogger(__name__).info(
            "Retrain threshold reached for user %s (%d corrections) "
            "but LIVE_TRAINING_ENABLED=false — skipping job dispatch.",
            body.user_id, pending_count,
        )

    return CorrectionsUploadResponse(
        accepted=accepted,
        retrain_triggered=retrain_triggered and settings.LIVE_TRAINING_ENABLED,
    )


@router.post(
    "/{correction_id}/audio",
    status_code=status.HTTP_200_OK,
    summary="Attach the audio a correction refers to",
)
async def upload_correction_audio(
    correction_id: str,
    audio: UploadFile = File(..., description="16kHz mono PCM WAV of the misrecognised utterance"),
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> dict:
    """
    A correction without audio cannot retrain an acoustic model: fine-tuning needs
    (audio, correct_text), and a (wrong_text, correct_text) pair supplies neither
    an input signal nor a way to measure the fix. Until the clip is attached, a
    correction can only inform the phrasebook, never the model itself.
    """
    result = await db.execute(
        select(CorrectionRecord).where(CorrectionRecord.correction_id == correction_id)
    )
    record = result.scalar_one_or_none()
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Correction not found")
    if record.user_id != current_user.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not your correction")

    data = await audio.read()
    if len(data) < 1000:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Audio too small ({len(data)} bytes) to be a usable training sample",
        )

    suffix = "." + (audio.filename.rsplit(".", 1)[-1] if audio.filename and "." in audio.filename else "wav")
    dest = get_correction_audio_path(record.user_id, correction_id, suffix)
    await save_upload(dest, data)
    record.audio_storage_path = str(dest)
    await db.commit()
    return {"correction_id": correction_id, "bytes": len(data), "stored": True}
