"""
VaaniMitra — FastAPI dependency injection helpers
"""
from __future__ import annotations

import uuid
from typing import AsyncGenerator

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import AsyncSessionLocal
from app.models.db_models import UserRecord


# ── Database session ──────────────────────────────────────────────────────────

async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

security = HTTPBearer(auto_error=True)


# ── Auth ─────────────────────────────────────────────────────────────────────
# Standard HTTP Bearer token check — integrates with OpenAPI /docs Authorize button.
# For hackathon scope: token == user_id (opaque, issued at /v1/auth/device).
# Replace with JWT validation when moving beyond hackathon.

async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: AsyncSession = Depends(get_db),
) -> UserRecord:
    token = credentials.credentials.strip()

    result = await db.execute(
        select(UserRecord).where(UserRecord.access_token_hash == token)
    )
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid or expired token")
    return user


security_optional = HTTPBearer(auto_error=False)


async def get_current_user_optional(
    credentials: HTTPAuthorizationCredentials | None = Depends(security_optional),
    db: AsyncSession = Depends(get_db),
) -> UserRecord | None:
    if credentials is None:
        return None
    token = credentials.credentials.strip()
    result = await db.execute(
        select(UserRecord).where(UserRecord.access_token_hash == token)
    )
    return result.scalar_one_or_none()


# ── Request ID ────────────────────────────────────────────────────────────────

def new_request_id() -> str:
    return str(uuid.uuid4())

