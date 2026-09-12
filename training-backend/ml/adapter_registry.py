"""
VaaniMitra — Adapter Registry
Scans ml/adapters/ at server startup. Any subdirectory containing a valid
adapter_manifest.json is upserted into the adapters DB table.

This is the static seed path:
  training-backend/ml/adapters/torgo_base_adapter_english_v1/
      adapter_manifest.json   ← defines type, language_code, etc.
      adapter_model.bin       ← drop your trained weights here

Despite the "clusters" endpoint name (kept as future-facing scaffolding for
severity-based selection), there is currently only one flat, global TORGO
adapter registered here (severity_cluster=null) — the abnerh/TORGO-database
mirror used to train it has no severity/speaker labels to cluster on.

The registry serves GET /v1/adapters/clusters?language=en → torgo_base_adapter_english_v1.
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db.database import AsyncSessionLocal
from app.models.db_models import AdapterRecord

logger = logging.getLogger(__name__)

REQUIRED_MANIFEST_KEYS = {"adapter_id", "type", "language_code", "base_model", "version", "weights_file"}


async def scan_and_register_adapters() -> None:
    """
    Walk ADAPTERS_DIR. For each subdirectory with adapter_manifest.json:
      - Validate manifest fields.
      - Upsert an AdapterRecord in the DB.
    Called from main.py startup event.
    """
    adapters_dir: Path = settings.ADAPTERS_DIR
    if not adapters_dir.exists():
        logger.warning("Adapters directory does not exist: %s", adapters_dir)
        return

    async with AsyncSessionLocal() as session:
        for adapter_dir in sorted(adapters_dir.iterdir()):
            if not adapter_dir.is_dir():
                continue
            manifest_path = adapter_dir / "adapter_manifest.json"
            if not manifest_path.exists():
                logger.debug("Skipping %s — no adapter_manifest.json", adapter_dir.name)
                continue
            await _register_from_manifest(session, adapter_dir, manifest_path)

        # Deactivate obsolete cluster adapters whose directories no longer exist on disk
        from sqlalchemy import select
        all_adapters = (await session.execute(select(AdapterRecord))).scalars().all()
        for rec in all_adapters:
            if rec.type == "CLUSTER" and not (adapters_dir / rec.adapter_id).is_dir():
                if rec.is_active:
                    rec.is_active = False
                    logger.info("Deactivated obsolete cluster adapter: %s", rec.adapter_id)
        await session.commit()


async def _register_from_manifest(
    session: AsyncSession,
    adapter_dir: Path,
    manifest_path: Path,
) -> None:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.error("Failed to parse %s: %s", manifest_path, exc)
        return

    missing = REQUIRED_MANIFEST_KEYS - manifest.keys()
    if missing:
        logger.error("Manifest %s missing required keys: %s", manifest_path, missing)
        return

    adapter_id: str = manifest["adapter_id"]
    weights_file: str = manifest["weights_file"]
    weights_path = adapter_dir / weights_file
    weights_present = weights_path.exists()

    if not weights_present:
        logger.warning(
            "Adapter '%s': manifest found but weights file '%s' not present yet. "
            "Drop the file into %s and restart to complete registration.",
            adapter_id, weights_file, adapter_dir,
        )

    # Compute checksum if weights present, else empty string.
    checksum = ""
    if weights_present:
        import hashlib
        data = weights_path.read_bytes()
        checksum = "sha256:" + hashlib.sha256(data).hexdigest()

    # Upsert
    from sqlalchemy import select
    result = await session.execute(select(AdapterRecord).where(AdapterRecord.adapter_id == adapter_id))
    existing = result.scalar_one_or_none()

    if existing is None:
        record = AdapterRecord(
            adapter_id=adapter_id,
            type=manifest["type"],
            user_id=None,
            language_code=manifest["language_code"],
            severity_cluster=manifest.get("severity_cluster"),
            base_model=manifest["base_model"],
            parent_adapter_id=manifest.get("parent_adapter_id"),
            version=manifest["version"],
            storage_path=str(weights_path) if weights_present else str(adapter_dir),
            checksum=checksum,
            is_active=True,
            created_at=datetime.now(timezone.utc),
        )
        session.add(record)
        logger.info("Registered new adapter: %s (type=%s, lang=%s, weights=%s)",
                    adapter_id, manifest["type"], manifest["language_code"],
                    "present" if weights_present else "MISSING — placeholder registered")
    else:
        updated = False
        if checksum and existing.checksum != checksum:
            existing.checksum = checksum
            updated = True
        if weights_present and existing.storage_path != str(weights_path):
            existing.storage_path = str(weights_path)
            updated = True
        if manifest.get("base_model") and existing.base_model != manifest["base_model"]:
            existing.base_model = manifest["base_model"]
            updated = True
        if updated:
            logger.info("Updated adapter weights & metadata for: %s", adapter_id)
        else:
            logger.debug("Adapter already registered (no changes): %s", adapter_id)
