"""
Local calibration fine-tune → ONNX mobile export pipeline.

Runs on laptop CPU/GPU (not cloud). Called from BackgroundTasks via train_worker.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Ensure unsupported MPS operations fall back to CPU instead of crashing on Mac Apple Silicon
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")

from app.config import settings
from app.services.session_status import write_status

logger = logging.getLogger(__name__)


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def _sync_db_session():
    """Sync SQLAlchemy session for background worker thread."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    url = str(settings.DATABASE_URL).replace("sqlite+aiosqlite", "sqlite")
    engine = create_engine(url, future=True)
    return sessionmaker(bind=engine, expire_on_commit=False)()


def _resolve_warm_start_adapter_dir() -> Path:
    adapter_id = settings.WARM_START_ADAPTER_ID
    path = settings.ADAPTERS_DIR / adapter_id
    if not (path / "adapter_manifest.json").is_file():
        raise FileNotFoundError(
            f"Warm-start adapter not found: {path}. "
            "Drop TORGO weights into ml/adapters/torgo_base_adapter_english_v1/"
        )
    weights = path / "adapter_model.safetensors"
    if not weights.is_file():
        weights = path / "adapter_model.bin"
    if not weights.is_file():
        raise FileNotFoundError(f"No adapter weights in {path}")
    return path


def _load_calibration_pairs(session_id: str) -> list[tuple[Path, str]]:
    """Load (audio_path, transcript) from DB samples for this session."""
    from app.models.db_models import CalibrationSampleRecord

    db = _sync_db_session()
    try:
        samples = (
            db.query(CalibrationSampleRecord)
            .filter(CalibrationSampleRecord.session_id == session_id)
            .order_by(CalibrationSampleRecord.uploaded_at)
            .all()
        )
        pairs: list[tuple[Path, str]] = []
        for s in samples:
            p = Path(s.audio_storage_path)
            if not p.is_file():
                logger.warning("Missing audio for sample %s: %s", s.sample_id, p)
                continue
            if p.stat().st_size < 1000:
                raise ValueError(f"Audio file too small / likely corrupt: {p.name}")
            pairs.append((p, s.prompt_text))
        if len(pairs) < 3:
            # Fallback: check ./sessions/{session_id}/manifest.json
            manifest_file = settings.SESSIONS_DIR / session_id / "manifest.json"
            if manifest_file.is_file():
                try:
                    data = json.loads(manifest_file.read_text())
                    mapping = data.get("mapping", data) if isinstance(data, dict) else {}
                    for wav_file in (settings.SESSIONS_DIR / session_id).glob("*.wav"):
                        txt = mapping.get(wav_file.name) or mapping.get(wav_file.stem)
                        if txt and wav_file.stat().st_size >= 500:
                            pairs.append((wav_file, str(txt).strip()))
                except Exception as ex:
                    logger.warning("Failed reading session manifest fallback: %s", ex)

        if len(pairs) < 3:
            raise ValueError(
                f"Need at least 3 calibration samples to fine-tune; got {len(pairs)}"
            )
        return pairs
    finally:
        db.close()


def _train_user_lora(
    audio_text_pairs: list[tuple[Path, str]],
    warm_start_dir: Path,
    output_dir: Path,
    base_model: str,
) -> Path:
    try:
        import torch
        import torchaudio
        from datasets import Dataset
        from peft import LoraConfig, PeftModel, get_peft_model
        from transformers import (
            Seq2SeqTrainer,
            Seq2SeqTrainingArguments,
            WhisperForConditionalGeneration,
            WhisperProcessor,
        )
    except ImportError as exc:
        # Fail loudly rather than substituting a placeholder adapter: a copy of the
        # warm-start weights would train "successfully", export, deploy to the phone,
        # and transcribe exactly like the un-personalized base model.
        raise RuntimeError(
            f"Training dependencies missing ({exc.name}). Real fine-tuning cannot run. "
            "Install them in the backend venv: "
            "pip install -r ml/requirements-training.txt"
        ) from exc

    if torch.cuda.is_available():
        device = torch.device("cuda")
    elif torch.backends.mps.is_available():
        device = torch.device("mps")
    else:
        device = torch.device("cpu")
    logger.info("Using device for fine-tuning: %s", device)

    logger.info("Loading base Whisper + merging warm-start TORGO adapter from %s", warm_start_dir)
    base = WhisperForConditionalGeneration.from_pretrained(base_model)
    merged_base = PeftModel.from_pretrained(base, str(warm_start_dir))
    model = merged_base.merge_and_unload()
    model.train()

    # Freeze encoder — decoder-only LoRA fine-tune
    for param in model.model.encoder.parameters():
        param.requires_grad = False

    lora_config = LoraConfig(
        r=4,
        lora_alpha=8,
        target_modules=["q_proj", "v_proj"],
        lora_dropout=0.05,
        bias="none",
    )
    model = get_peft_model(model, lora_config)
    model.to(device)
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    logger.info("Trainable parameters: %s on device: %s", trainable, device)

    processor = WhisperProcessor.from_pretrained(base_model)

    def load_audio(path: Path) -> torch.Tensor:
        wav, sr = torchaudio.load(str(path))
        if wav.shape[0] > 1:
            wav = wav.mean(dim=0, keepdim=True)
        if sr != 16000:
            wav = torchaudio.functional.resample(wav, sr, 16000)
        return wav.squeeze(0)

    rows = []
    for audio_path, text in audio_text_pairs:
        rows.append({"audio_path": str(audio_path), "text": text.strip()})

    dataset = Dataset.from_list(rows)

    def prepare(example: dict) -> dict:
        wav = load_audio(Path(example["audio_path"]))
        inputs = processor(
            wav.numpy(),
            sampling_rate=16000,
            return_tensors="pt",
        )
        labels = processor.tokenizer(
            example["text"],
            return_tensors="pt",
        ).input_ids.squeeze(0)
        return {
            "input_features": inputs.input_features.squeeze(0),
            "labels": labels,
        }

    tokenized = dataset.map(prepare, remove_columns=dataset.column_names)

    class WhisperDataCollator:
        def __init__(self, proc: WhisperProcessor):
            self.proc = proc

        def __call__(self, features: list[dict]) -> dict:
            input_features = torch.stack([f["input_features"] for f in features])
            label_list = [f["labels"] for f in features]
            labels = torch.nn.utils.rnn.pad_sequence(
                label_list, batch_first=True, padding_value=-100
            )
            return {"input_features": input_features, "labels": labels}

    output_dir.mkdir(parents=True, exist_ok=True)
    training_args = Seq2SeqTrainingArguments(
        output_dir=str(output_dir / "checkpoints"),
        per_device_train_batch_size=2,
        num_train_epochs=4,
        learning_rate=2e-4,
        eval_strategy="no",
        predict_with_generate=True,
        logging_steps=1,
        save_strategy="no",
        remove_unused_columns=False,
        label_names=["labels"],
        report_to=[],
    )

    trainer = Seq2SeqTrainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
        data_collator=WhisperDataCollator(processor),
    )

    train_result = trainer.train()
    losses = [h["loss"] for h in trainer.state.log_history if "loss" in h]
    if losses:
        logger.info(
            "Training loss: first=%.4f last=%.4f (steps=%d)",
            losses[0],
            losses[-1],
            len(losses),
        )
        if len(losses) > 1 and abs(losses[-1] - losses[0]) < 1e-6:
            logger.warning("Loss barely changed — verify audio/transcripts are valid")
    else:
        logger.warning("No loss logged during training")

    adapter_out = output_dir / "lora_adapter"
    model.save_pretrained(str(adapter_out))
    processor.save_pretrained(str(adapter_out))
    (adapter_out / "train_metrics.json").write_text(
        json.dumps({"loss_history": losses, "train_result": train_result.metrics}, indent=2)
    )
    return adapter_out


def _export_mobile_bundle(adapter_dir: Path, adapter_id: str) -> Path:
    from ml.export_whisper_mobile import build_bundle, export_onnx, load_merged_model, quantize_dynamic, sha256_file

    export_dir = settings.MOBILE_EXPORT_DIR / adapter_id
    if export_dir.exists():
        shutil.rmtree(export_dir)
    export_dir.mkdir(parents=True)

    merged = load_merged_model(adapter_dir, settings.WHISPER_BASE_MODEL)
    encoder, decoder = export_onnx(merged, export_dir)

    enc_int8 = quantize_dynamic(encoder, export_dir / "encoder_model_int8.onnx")
    dec_int8 = quantize_dynamic(decoder, export_dir / "decoder_model_int8.onnx")

    for name in ("tokenizer.json", "tokenizer_config.json", "preprocessor_config.json"):
        src = adapter_dir / name
        if src.is_file() and not (export_dir / name).exists():
            shutil.copy2(src, export_dir / name)

    manifest = {
        "adapter_id": adapter_id,
        "base_model": settings.WHISPER_BASE_MODEL,
        "format": "onnx",
        "merged_lora": True,
        "encoder_file": "encoder_model_int8.onnx",
        "decoder_file": "decoder_model_int8.onnx",
        "sample_rate": 16000,
        "n_mels": 80,
        "quantization": "dynamic_quint8",
        "checksums": {
            "encoder": sha256_file(enc_int8),
            "decoder": sha256_file(dec_int8),
        },
    }
    (export_dir / "mobile_manifest.json").write_text(json.dumps(manifest, indent=2))
    zip_path = build_bundle(export_dir, adapter_id, use_int8=True)
    return zip_path


def _register_user_adapter(
    adapter_id: str,
    user_id: str,
    lora_dir: Path,
    job_id: str,
    session_id: str,
) -> None:
    from app.models.db_models import AdapterRecord, CalibrationSessionRecord, TrainingJobRecord

    dest = settings.ADAPTERS_DIR / adapter_id
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(lora_dir, dest)

    manifest = {
        "adapter_id": adapter_id,
        "type": "USER",
        "language_code": "en",
        "base_model": settings.WHISPER_BASE_MODEL,
        "version": 1,
        "weights_file": "adapter_model.safetensors",
        "parent_adapter_id": settings.WARM_START_ADAPTER_ID,
    }
    weights = dest / "adapter_model.safetensors"
    if not weights.is_file():
        manifest["weights_file"] = "adapter_model.bin"
    (dest / "adapter_manifest.json").write_text(json.dumps(manifest, indent=2))

    checksum = _sha256_file(dest / manifest["weights_file"]) if (dest / manifest["weights_file"]).is_file() else ""

    db = _sync_db_session()
    try:
        existing = db.query(AdapterRecord).filter(AdapterRecord.adapter_id == adapter_id).first()
        if existing:
            existing.storage_path = str(dest / manifest["weights_file"])
            existing.checksum = checksum
            existing.is_active = True
            existing.training_job_id = job_id
        else:
            db.add(
                AdapterRecord(
                    adapter_id=adapter_id,
                    type="USER",
                    user_id=user_id,
                    language_code="en",
                    base_model=settings.WHISPER_BASE_MODEL,
                    parent_adapter_id=settings.WARM_START_ADAPTER_ID,
                    version=1,
                    storage_path=str(dest / manifest["weights_file"]),
                    checksum=checksum,
                    training_job_id=job_id,
                    is_active=True,
                )
            )

        job = db.query(TrainingJobRecord).filter(TrainingJobRecord.job_id == job_id).first()
        if job:
            job.status = "COMPLETE"
            job.resulting_adapter_id = adapter_id
            job.completed_at = datetime.now(timezone.utc)

        session = db.query(CalibrationSessionRecord).filter(
            CalibrationSessionRecord.session_id == session_id
        ).first()
        if session:
            session.status = "COMPLETE"
            session.updated_at = datetime.now(timezone.utc)

        db.commit()
    finally:
        db.close()


def run_finetune(session_id: str, user_id: str, job_id: str | None = None) -> dict[str, Any]:
    """
    Full pipeline: fine-tune user LoRA on calibration audio → export ONNX INT8 bundle.

    Updates status.json phases: training → exporting → ready | failed
    """
    job_id = job_id or str(uuid.uuid4())
    adapter_id = f"user_{user_id}"

    try:
        write_status(
            session_id,
            status="training",
            job_id=job_id,
            adapter_id=adapter_id,
            message="Loading calibration samples",
        )

        pairs = _load_calibration_pairs(session_id)
        warm_start = _resolve_warm_start_adapter_dir()
        work_dir = settings.SESSIONS_DIR / session_id / "training"
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)

        write_status(session_id, status="training", message=f"Fine-tuning on {len(pairs)} samples")
        lora_dir = _train_user_lora(
            pairs,
            warm_start_dir=warm_start,
            output_dir=work_dir,
            base_model=settings.WHISPER_BASE_MODEL,
        )

        write_status(session_id, status="exporting", message="Exporting ONNX mobile bundle")
        bundle_zip = _export_mobile_bundle(lora_dir, adapter_id)

        _register_user_adapter(adapter_id, user_id, lora_dir, job_id, session_id)

        write_status(
            session_id,
            status="ready",
            adapter_id=adapter_id,
            mobile_bundle=str(bundle_zip),
            message="Adapter ready for download",
        )
        logger.info("run_finetune complete session=%s adapter=%s", session_id, adapter_id)
        return {"adapter_id": adapter_id, "status": "ready", "job_id": job_id}

    except Exception as exc:
        logger.exception("run_finetune failed session=%s: %s", session_id, exc)
        write_status(
            session_id,
            status="failed",
            job_id=job_id,
            error=str(exc),
            message="Training failed",
        )
        _mark_session_failed(session_id, job_id, str(exc))
        raise


def _mark_session_failed(session_id: str, job_id: str, error: str) -> None:
    from app.models.db_models import CalibrationSessionRecord, TrainingJobRecord

    db = _sync_db_session()
    try:
        session = db.query(CalibrationSessionRecord).filter(
            CalibrationSessionRecord.session_id == session_id
        ).first()
        if session:
            session.status = "FAILED"
            session.updated_at = datetime.now(timezone.utc)
        job = db.query(TrainingJobRecord).filter(TrainingJobRecord.job_id == job_id).first()
        if job:
            job.status = "FAILED"
            job.error_message = error
            job.completed_at = datetime.now(timezone.utc)
        db.commit()
    finally:
        db.close()
