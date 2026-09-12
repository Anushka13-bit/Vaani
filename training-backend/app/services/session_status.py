"""
Pollable per-session status for calibration → fine-tune → export pipeline.

Written to: {SESSIONS_DIR}/{session_id}/status.json
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

from app.config import settings

SessionPhase = Literal["queued", "training", "exporting", "ready", "failed"]


def session_dir(session_id: str) -> Path:
    return settings.SESSIONS_DIR / session_id


def status_path(session_id: str) -> Path:
    return session_dir(session_id) / "status.json"


def write_status(session_id: str, **fields: Any) -> None:
    path = status_path(session_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = {"session_id": session_id, "updated_at": _now_iso()}
    if path.exists():
        try:
            payload.update(json.loads(path.read_text(encoding="utf-8")))
        except Exception:
            pass
    payload.update(fields)
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def read_status(session_id: str) -> dict[str, Any] | None:
    path = status_path(session_id)
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()
