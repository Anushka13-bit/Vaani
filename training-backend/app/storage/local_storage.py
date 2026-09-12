"""
VaaniMitra — Local disk storage stub
Replaces S3/GCS for hackathon scope. Files are saved under STORAGE_ROOT.
Swap this module for a real object storage client when scaling up.
"""
from __future__ import annotations

import hashlib
import shutil
from pathlib import Path

import aiofiles

from app.config import settings

STORAGE_ROOT = settings.STORAGE_ROOT


def _ensure_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def get_sample_path(session_id: str, sample_id: str, suffix: str = ".wav") -> Path:
    return _ensure_dir(STORAGE_ROOT / "calibration" / session_id) / f"{sample_id}{suffix}"


def get_correction_audio_path(user_id: str, correction_id: str, suffix: str = ".wav") -> Path:
    return _ensure_dir(STORAGE_ROOT / "corrections" / user_id) / f"{correction_id}{suffix}"


def get_adapter_path(adapter_id: str, filename: str = "adapter_model.bin") -> Path:
    return _ensure_dir(STORAGE_ROOT / "adapters" / adapter_id) / filename


async def save_upload(dest: Path, data: bytes) -> str:
    """Write bytes to dest, return sha256 checksum."""
    _ensure_dir(dest.parent)
    async with aiofiles.open(dest, "wb") as f:
        await f.write(data)
    return _sha256(data)


async def read_file(path: Path) -> bytes:
    async with aiofiles.open(path, "rb") as f:
        return await f.read()


def file_exists(path: Path) -> bool:
    return path.exists() and path.is_file()


def delete_file(path: Path) -> None:
    if path.exists():
        path.unlink(missing_ok=True)


def _sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def public_url(adapter_id: str, filename: str = "adapter_model.bin") -> str:
    """
    Returns a relative download URL. In production this would be a pre-signed S3 URL.
    For local dev: the /v1/adapters/{id}/download endpoint serves the file directly.
    """
    return f"/v1/adapters/{adapter_id}/download"
