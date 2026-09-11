"""
Session-scoped adapter endpoints for calibration pipeline polling.

GET /v1/adapter/{session_id}/status  — poll training/export status
GET /v1/adapter/{session_id}         — download mobile ONNX bundle when ready
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import FileResponse

from app.config import settings
from app.deps import get_current_user_optional
from app.models.db_models import UserRecord
from app.models.pydantic_models import SessionAdapterStatusResponse
from app.services.session_status import read_status

router = APIRouter(prefix="/adapter", tags=["session-adapter"])


@router.get(
    "/{session_id}/status",
    response_model=SessionAdapterStatusResponse,
    summary="Poll per-session fine-tune / export status",
)
async def get_session_adapter_status(
    session_id: str,
    _: UserRecord | None = Depends(get_current_user_optional),
) -> SessionAdapterStatusResponse:
    data = read_status(session_id)
    if data is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No training status for this session. Trigger training first.",
        )
    phase = data.get("status", "queued")
    progress = {
        "queued": 5,
        "training": 40,
        "exporting": 80,
        "ready": 100,
        "failed": 0,
    }.get(phase, 0)

    return SessionAdapterStatusResponse(
        session_id=session_id,
        status=phase,
        progress_pct=progress,
        adapter_id=data.get("adapter_id"),
        job_id=data.get("job_id"),
        message=data.get("message"),
        error=data.get("error"),
    )


@router.get(
    "/{session_id}",
    summary="Download mobile ONNX bundle for a completed calibration session",
    response_class=FileResponse,
)
async def download_session_adapter(
    session_id: str,
    _: UserRecord | None = Depends(get_current_user_optional),
) -> FileResponse:
    data = read_status(session_id)
    if data is None or data.get("status") != "ready":
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Adapter not ready. Poll /adapter/{session_id}/status until status=ready.",
        )

    adapter_id = data.get("adapter_id")
    if not adapter_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="adapter_id missing")

    bundle = settings.MOBILE_EXPORT_DIR / adapter_id / "mobile_bundle.zip"
    if not bundle.is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Mobile bundle not found for adapter '{adapter_id}'.",
        )

    return FileResponse(
        path=str(bundle),
        media_type="application/zip",
        filename=f"{adapter_id}_mobile_bundle.zip",
    )
