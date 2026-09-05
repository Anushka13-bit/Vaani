"""
VaaniMitra — Corrections Router (§5.4)
POST /v1/corrections — batch upload user corrections (opt-in only)
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_current_user, get_db
from app.models.db_models import CorrectionRecord, UserRecord
from app.models.pydantic_models import CorrectionsUploadRequest, CorrectionsUploadResponse

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
