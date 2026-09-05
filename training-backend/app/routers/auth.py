"""
VaaniMitra — Auth Router (§5.1)
POST /v1/auth/device — register device/user pair, issue access token.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import AsyncSessionLocal
from app.deps import get_db
from app.models.db_models import UserRecord
from app.models.pydantic_models import DeviceRegisterRequest, DeviceRegisterResponse

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post(
    "/device",
    response_model=DeviceRegisterResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Register or re-authenticate a device",
)
async def register_device(
    body: DeviceRegisterRequest,
    db: AsyncSession = Depends(get_db),
) -> DeviceRegisterResponse:
    """
    Registers a device/user pair and issues a bearer token.
    If the device_id already exists, refreshes the token.
    """
    result = await db.execute(
        select(UserRecord).where(UserRecord.device_id == body.device_id)
    )
    user = result.scalar_one_or_none()

    token = str(uuid.uuid4())  # opaque token (replace with JWT for production)
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)

    if user is None:
        user = UserRecord(
            user_id=str(uuid.uuid4()),
            device_id=body.device_id,
            preferred_language=body.preferred_language,
            dysarthria_severity_hint=body.dysarthria_severity_hint,
            access_token_hash=token,
            token_expires_at=expires_at,
        )
        db.add(user)
    else:
        # Refresh token
        user.access_token_hash = token
        user.token_expires_at = expires_at
        if body.dysarthria_severity_hint:
            user.dysarthria_severity_hint = body.dysarthria_severity_hint

    await db.flush()

    return DeviceRegisterResponse(
        user_id=user.user_id,
        access_token=token,
        expires_at=expires_at,
    )
