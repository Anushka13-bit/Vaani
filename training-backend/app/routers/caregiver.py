"""
VaaniMitra — Caregiver Router (§5.5)
POST /v1/caregiver/link
GET  /v1/caregiver/{user_id}/transcripts
PUT  /v1/caregiver/{user_id}/phrasebook/{entry_id}
"""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.deps import get_current_user, get_db
from app.models.db_models import (
    CaregiverLinkRecord,
    CaregiverPhrasebookRecord,
    TranscriptLogRecord,
    UserRecord,
)
from app.models.pydantic_models import (
    CaregiverLinkRequest,
    CaregiverLinkResponse,
    PhrasebookUpdateRequest,
    PhrasebookUpdateResponse,
    TranscriptEntry,
    TranscriptListResponse,
)

router = APIRouter(prefix="/caregiver", tags=["caregiver"])


# ── POST /caregiver/link ──────────────────────────────────────────────────────

@router.post(
    "/link",
    response_model=CaregiverLinkResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Request caregiver link (requires user approval)",
)
async def link_caregiver(
    body: CaregiverLinkRequest,
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> CaregiverLinkResponse:
    """
    Creates a PENDING_USER_APPROVAL link. The user must approve it
    (via a separate /caregiver/link/{id}/approve endpoint — TODO for v2)
    before any caregiver data endpoints return data.
    """
    if current_user.user_id != body.user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="user_id mismatch")

    # Prevent duplicate links for the same caregiver email
    existing = await db.execute(
        select(CaregiverLinkRecord).where(
            CaregiverLinkRecord.user_id == body.user_id,
            CaregiverLinkRecord.caregiver_email == body.caregiver_email,
        )
    )
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Caregiver link already exists")

    link = CaregiverLinkRecord(
        caregiver_id=str(uuid.uuid4()),
        user_id=body.user_id,
        caregiver_email=body.caregiver_email,
        permissions=",".join(body.permissions),
        status="PENDING_USER_APPROVAL",
        linked_at=datetime.now(timezone.utc),
    )
    db.add(link)
    await db.flush()

    return CaregiverLinkResponse(
        caregiver_id=link.caregiver_id,
        status=link.status,
    )


# ── GET /caregiver/{user_id}/transcripts ─────────────────────────────────────

@router.get(
    "/{user_id}/transcripts",
    response_model=TranscriptListResponse,
    summary="Get low-confidence transcripts for caregiver review",
)
async def get_transcripts(
    user_id: str,
    confidence_lt: float = Query(default=0.6, ge=0.0, le=1.0),
    limit: int = Query(default=50, ge=1, le=200),
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> TranscriptListResponse:
    # Verify caller has an APPROVED caregiver link for this user
    link_result = await db.execute(
        select(CaregiverLinkRecord).where(
            CaregiverLinkRecord.user_id == user_id,
            CaregiverLinkRecord.status == "APPROVED",
        ).limit(1)
    )
    link = link_result.scalar_one_or_none()
    if link is None:
        # Also allow the user themselves to see their own transcripts
        if current_user.user_id != user_id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="No approved caregiver link found for this user.",
            )

    result = await db.execute(
        select(TranscriptLogRecord)
        .where(
            TranscriptLogRecord.user_id == user_id,
            TranscriptLogRecord.confidence < confidence_lt,
        )
        .order_by(TranscriptLogRecord.timestamp.desc())
        .limit(limit)
    )
    rows = result.scalars().all()

    return TranscriptListResponse(
        transcripts=[
            TranscriptEntry(
                id=r.id,
                transcript=r.transcript,
                confidence=r.confidence,
                timestamp=r.timestamp,
            )
            for r in rows
        ]
    )


# ── PUT /caregiver/{user_id}/phrasebook/{entry_id} ────────────────────────────

@router.put(
    "/{user_id}/phrasebook/{entry_id}",
    response_model=PhrasebookUpdateResponse,
    summary="Caregiver creates or updates a phrasebook entry for the user",
)
async def upsert_phrasebook_entry(
    user_id: str,
    entry_id: str,
    body: PhrasebookUpdateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> PhrasebookUpdateResponse:
    # Require an approved caregiver link with EDIT_PHRASEBOOK permission
    link_result = await db.execute(
        select(CaregiverLinkRecord).where(
            CaregiverLinkRecord.user_id == user_id,
            CaregiverLinkRecord.status == "APPROVED",
        ).limit(1)
    )
    link = link_result.scalar_one_or_none()

    if link is None:
        if current_user.user_id != user_id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN,
                                detail="No approved caregiver link found.")
        caregiver_id = current_user.user_id  # user editing their own phrasebook
    else:
        if "EDIT_PHRASEBOOK" not in link.permissions:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN,
                                detail="Caregiver does not have EDIT_PHRASEBOOK permission.")
        caregiver_id = link.caregiver_id

    # Upsert
    existing = await db.execute(
        select(CaregiverPhrasebookRecord).where(
            CaregiverPhrasebookRecord.entry_id == entry_id
        )
    )
    entry = existing.scalar_one_or_none()

    if entry is None:
        entry = CaregiverPhrasebookRecord(
            entry_id=entry_id,
            caregiver_id=caregiver_id,
            user_id=user_id,
            trigger_phrase=body.trigger_phrase,
            action_type=body.action_type,
            action_payload_json=json.dumps(body.action_payload),
        )
        db.add(entry)
    else:
        entry.trigger_phrase = body.trigger_phrase
        entry.action_type = body.action_type
        entry.action_payload_json = json.dumps(body.action_payload)
        entry.updated_at = datetime.now(timezone.utc)

    await db.flush()

    return PhrasebookUpdateResponse(entry_id=entry.entry_id, updated=True)
