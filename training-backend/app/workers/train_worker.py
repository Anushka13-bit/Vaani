"""
VaaniMitra — Training Worker (STUB)
Live training pipeline is disabled. Drop your pre-trained LoRA adapter weights
into training-backend/ml/adapters/<adapter_id>/ and they will be picked up
by the adapter_registry at server startup.

STUB — live training disabled.
Uncomment and implement when enabling GPU-backed training.
"""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


async def run_training_job(job_id: str, session_id: str, user_id: str) -> None:
    """
    Entry point for a LoRA fine-tuning job.

    STUB — live training disabled.
    Replace this body with a call to ml.train_lora_whisper.train() when
    LIVE_TRAINING_ENABLED=true and GPU dependencies are installed.
    """
    raise NotImplementedError(
        "Live training pipeline is not enabled. "
        "Drop adapter weights into training-backend/ml/adapters/ and restart the server. "
        "Set LIVE_TRAINING_ENABLED=true in config to enable GPU training."
    )
