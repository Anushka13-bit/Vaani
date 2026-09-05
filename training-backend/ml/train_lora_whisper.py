"""
VaaniMitra — LoRA Whisper Training Script (STUB)
Live training pipeline is disabled. This module exists so imports don't break.

STUB — live training disabled.
Set LIVE_TRAINING_ENABLED=true and install GPU deps to activate.

Original training logic will be implemented here:
  - Load base Whisper + warm-start PEFT adapter
  - Load + preprocess calibration samples (resample 16kHz, log-mel features)
  - Fine-tune LoRA adapter (small epoch count, small LR)
  - Evaluate WER on held-out subset
  - Save adapter weights to object storage
  - Return storage path + checksum
"""
from __future__ import annotations

from typing import Any


def train(
    session_id: str,
    user_id: str,
    warm_start_adapter_path: str | None,
    calibration_audio_paths: list[str],
    prompt_texts: list[str],
    language: str = "en",
    base_model: str = "openai/whisper-small",
    **kwargs: Any,
) -> dict[str, Any]:
    """
    Train a LoRA adapter for a single user calibration session.

    Returns:
        {"adapter_path": str, "checksum": str, "wer": float}

    STUB — raises NotImplementedError until live training is enabled.
    """
    raise NotImplementedError(
        "train_lora_whisper.train() is a stub. "
        "Live training is disabled (LIVE_TRAINING_ENABLED=false). "
        "Drop your pre-trained adapter weights into ml/adapters/ and restart."
    )
