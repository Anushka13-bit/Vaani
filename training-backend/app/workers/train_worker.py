"""
VaaniMitra — Training Worker
Dispatches local fine-tune jobs (BackgroundTasks / thread pool).
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from app.config import settings

logger = logging.getLogger(__name__)


def run_training_job(job_id: str, session_id: str, user_id: str) -> None:
    """
    Synchronous entry point for BackgroundTasks.
    Calls ml.run_finetune.run_finetune() when LIVE_TRAINING_ENABLED=true.
    """
    if not settings.LIVE_TRAINING_ENABLED:
        raise RuntimeError(
            "LIVE_TRAINING_ENABLED=false. Set LIVE_TRAINING_ENABLED=true in .env "
            "and install ml/requirements-training.txt"
        )

    from ml.run_finetune import run_finetune

    logger.info("Starting training job %s session=%s user=%s", job_id, session_id, user_id)
    run_finetune(session_id=session_id, user_id=user_id, job_id=job_id)


def _sync_db_session():
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    url = str(settings.DATABASE_URL).replace("sqlite+aiosqlite", "sqlite")
    engine = create_engine(url, future=True)
    return sessionmaker(bind=engine, expire_on_commit=False)()


def create_job_record(session_id: str, user_id: str) -> str:
    """Create TrainingJobRecord in DB (sync) and return job_id."""
    from app.models.db_models import CalibrationSessionRecord, TrainingJobRecord

    job_id = str(uuid.uuid4())
    db = _sync_db_session()
    try:
        db.add(
            TrainingJobRecord(
                job_id=job_id,
                session_id=session_id,
                user_id=user_id,
                trigger="CALIBRATION",
                status="QUEUED",
                started_at=datetime.now(timezone.utc),
            )
        )
        session = db.query(CalibrationSessionRecord).filter(
            CalibrationSessionRecord.session_id == session_id
        ).first()
        if session:
            session.status = "TRAINING"
            session.updated_at = datetime.now(timezone.utc)
        db.commit()
    finally:
        db.close()
    return job_id
