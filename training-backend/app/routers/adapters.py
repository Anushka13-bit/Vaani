"""
VaaniMitra — Adapters Router (§5.3)
GET /v1/adapters/{adapter_id}
GET /v1/adapters/{adapter_id}/download
GET /v1/adapters/clusters
GET /v1/users/{user_id}/adapter
"""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import FileResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.deps import get_current_user, get_db
from app.models.db_models import AdapterRecord, UserRecord
from app.models.pydantic_models import (
    AdapterMetaResponse,
    ClusterAdapterResponse,
    UserAdapterResponse,
)
from app.storage.local_storage import public_url

router = APIRouter(tags=["adapters"])


def _download_url(adapter_id: str) -> str:
    return public_url(adapter_id)


def _resolve_adapter_file(record: AdapterRecord) -> Path | None:
    """
    Resolves the actual file path for an adapter.
    Checks storage_path directly; also checks the ml/adapters/ handoff dir.
    """
    path = Path(record.storage_path)
    if path.is_file():
        return path

    # Try handoff directory
    handoff = settings.ADAPTERS_DIR / record.adapter_id / "adapter_model.bin"
    if handoff.is_file():
        return handoff

    # Try safetensors variant
    handoff_st = settings.ADAPTERS_DIR / record.adapter_id / "adapter_model.safetensors"
    if handoff_st.is_file():
        return handoff_st

    return None


# ── GET /adapters/clusters ────────────────────────────────────────────────────
# Must be defined before /{adapter_id} to avoid path ambiguity.

@router.get(
    "/adapters/clusters",
    response_model=ClusterAdapterResponse,
    summary="Get best-matching cluster adapter for warm-starting a new user",
)
async def get_cluster_adapter(
    language: str = Query(..., description="BCP-47 language code, e.g. en, ta"),
    severity: str | None = Query(default=None, description="Dysarthria severity: mild | moderate | severe"),
    db: AsyncSession = Depends(get_db),
    _: UserRecord = Depends(get_current_user),
) -> ClusterAdapterResponse:
    query = (
        select(AdapterRecord)
        .where(
            AdapterRecord.type == "CLUSTER",
            AdapterRecord.language_code == language,
            AdapterRecord.is_active.is_(True),
        )
    )
    if severity:
        query = query.where(AdapterRecord.severity_cluster == severity)

    query = query.order_by(AdapterRecord.version.desc()).limit(1)
    result = await db.execute(query)
    adapter = result.scalar_one_or_none()

    if adapter is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No cluster adapter found for language='{language}'"
                   + (f", severity='{severity}'" if severity else "")
                   + ". Ensure adapter_model.bin is dropped into ml/adapters/ and server restarted.",
        )

    return ClusterAdapterResponse(
        adapter_id=adapter.adapter_id,
        version=adapter.version,
        download_url=_download_url(adapter.adapter_id),
    )


# ── GET /adapters/{adapter_id} ────────────────────────────────────────────────

@router.get(
    "/adapters/{adapter_id}",
    response_model=AdapterMetaResponse,
    summary="Get adapter metadata",
)
async def get_adapter(
    adapter_id: str,
    db: AsyncSession = Depends(get_db),
    _: UserRecord = Depends(get_current_user),
) -> AdapterMetaResponse:
    result = await db.execute(
        select(AdapterRecord).where(AdapterRecord.adapter_id == adapter_id)
    )
    adapter = result.scalar_one_or_none()
    if adapter is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Adapter not found")

    return AdapterMetaResponse(
        adapter_id=adapter.adapter_id,
        type=adapter.type,
        language_code=adapter.language_code,
        severity_cluster=adapter.severity_cluster,
        version=adapter.version,
        checksum=adapter.checksum,
        download_url=_download_url(adapter.adapter_id),
    )


# ── GET /adapters/{adapter_id}/download ──────────────────────────────────────

@router.get(
    "/adapters/{adapter_id}/download",
    summary="Download adapter weights binary",
    response_class=FileResponse,
)
async def download_adapter(
    adapter_id: str,
    db: AsyncSession = Depends(get_db),
    _: UserRecord = Depends(get_current_user),
) -> FileResponse:
    result = await db.execute(
        select(AdapterRecord).where(AdapterRecord.adapter_id == adapter_id)
    )
    adapter = result.scalar_one_or_none()
    if adapter is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Adapter not found")

    file_path = _resolve_adapter_file(adapter)
    if file_path is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                f"Adapter '{adapter_id}' is registered but weights file not found. "
                f"Drop adapter_model.bin into ml/adapters/{adapter_id}/ and restart the server."
            ),
        )

    return FileResponse(
        path=str(file_path),
        media_type="application/octet-stream",
        filename=f"{adapter_id}.bin",
    )


# ── GET /users/{user_id}/adapter ─────────────────────────────────────────────

@router.get(
    "/users/{user_id}/adapter",
    response_model=UserAdapterResponse,
    summary="Get the currently active adapter for a user",
)
async def get_user_adapter(
    user_id: str,
    db: AsyncSession = Depends(get_db),
    current_user: UserRecord = Depends(get_current_user),
) -> UserAdapterResponse:
    if current_user.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Forbidden")

    result = await db.execute(
        select(AdapterRecord)
        .where(
            AdapterRecord.user_id == user_id,
            AdapterRecord.type == "USER",
            AdapterRecord.is_active.is_(True),
        )
        .order_by(AdapterRecord.version.desc())
        .limit(1)
    )
    adapter = result.scalar_one_or_none()

    if adapter is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No active user adapter found. Complete calibration first.",
        )

    return UserAdapterResponse(
        adapter_id=adapter.adapter_id,
        version=adapter.version,
        updated_at=adapter.created_at,
    )
