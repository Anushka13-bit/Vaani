"""
VaaniMitra — LoRA Whisper training entrypoint.

Delegates to ml.run_finetune for per-session calibration fine-tune.
"""
from __future__ import annotations

from typing import Any

from ml.run_finetune import run_finetune


def train(
    session_id: str,
    user_id: str,
    warm_start_adapter_path: str | None = None,
    calibration_audio_paths: list[str] | None = None,
    prompt_texts: list[str] | None = None,
    language: str = "en",
    base_model: str = "openai/whisper-small",
    **kwargs: Any,
) -> dict[str, Any]:
    """Train + export a user adapter for a calibration session."""
    return run_finetune(session_id=session_id, user_id=user_id)
