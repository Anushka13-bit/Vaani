"""
VaaniMitra Training Backend — Configuration
All settings are read from environment variables or .env file.
"""
from __future__ import annotations

import os
from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # ── App ──────────────────────────────────────────────────────────────────
    APP_NAME: str = "VaaniMitra Training Backend"
    API_VERSION: str = "v1"
    DEBUG: bool = False

    # ── Security ─────────────────────────────────────────────────────────────
    SECRET_KEY: str = "CHANGE_ME_IN_PRODUCTION_use_openssl_rand_hex_32"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60 * 24 * 7  # 7 days

    # ── Database ─────────────────────────────────────────────────────────────
    DATABASE_URL: str = "sqlite+aiosqlite:///./vaanimitra.db"

    # ── Storage ──────────────────────────────────────────────────────────────
    # Local disk storage root (S3-compatible stub for hackathon).
    # Change to S3_BUCKET / GCS_BUCKET etc. when moving to cloud.
    STORAGE_ROOT: Path = Path("./storage_data")

    # ── Adapters ─────────────────────────────────────────────────────────────
    # Path that adapter_registry.py scans for static seed adapters.
    ADAPTERS_DIR: Path = Path(__file__).parent.parent / "ml" / "adapters"

    # ONNX mobile bundles produced by ml/export_whisper_mobile.py
    MOBILE_EXPORT_DIR: Path = Path(__file__).parent.parent / "ml" / "mobile_export"

    # Per-session training workspace (status.json + batch uploads)
    SESSIONS_DIR: Path = Path(__file__).parent.parent / "sessions"

    # Whisper base model identifier (used in adapter records).
    # Must match the model your LoRA adapters were trained against.
    WHISPER_BASE_MODEL: str = "openai/whisper-small"

    # Cluster adapter used as warm-start for per-user fine-tune (TORGO English).
    CLUSTER_WARM_START_ADAPTER: str = "torgo_cluster_english_v1"

    # ── Training ─────────────────────────────────────────────────────────────
    # Local laptop fine-tune. Requires: pip install -r ml/requirements-training.txt
    LIVE_TRAINING_ENABLED: bool = True

    # Threshold of unsynced corrections before a retrain job is auto-triggered.
    CORRECTION_RETRAIN_THRESHOLD: int = 20

    # Calibration audio retention policy in days. 0 = delete immediately after training.
    AUDIO_RETENTION_DAYS: int = 30

    # ── Calibration prompts ───────────────────────────────────────────────────
    DEFAULT_SAMPLE_COUNT: int = 5


settings = Settings()
