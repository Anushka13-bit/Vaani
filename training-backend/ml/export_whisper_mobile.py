#!/usr/bin/env python3
"""
VaaniMitra — Export merged TORGO LoRA Whisper to a mobile ONNX bundle.

Usage (from training-backend/, with GPU optional):
  pip install -r ml/requirements-export.txt
  python ml/export_whisper_mobile.py \\
    --adapter-dir ml/adapters/torgo_base_adapter_english_v1 \\
    --output-dir ml/mobile_export/torgo_base_adapter_english_v1

Produces:
  mobile_export/<adapter_id>/
    encoder_model.onnx          # merged LoRA baked in
    decoder_model.onnx
    encoder_model_int8.onnx     # optional quantized
    decoder_model_int8.onnx
    mobile_manifest.json        # checksums, shapes, WER sanity report
    mobile_bundle.zip           # what the Android app downloads
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import shutil
import tempfile
import zipfile
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def load_merged_model(adapter_dir: Path, base_model: str):
    import torch
    from peft import PeftModel
    from transformers import WhisperForConditionalGeneration

    logger.info("Loading base model: %s", base_model)
    base = WhisperForConditionalGeneration.from_pretrained(base_model)

    weights = adapter_dir / "adapter_model.safetensors"
    if not weights.exists():
        weights = adapter_dir / "adapter_model.bin"
    if not weights.exists():
        raise FileNotFoundError(f"No adapter weights in {adapter_dir}")

    logger.info("Loading LoRA adapter from %s", weights)
    model = PeftModel.from_pretrained(base, adapter_dir)
    model = model.merge_and_unload()
    model.eval()
    return model


def export_onnx(model, output_dir: Path) -> tuple[Path, Path]:
    # main_export (not export) is the entry point that takes a checkpoint path, a task
    # and splits an encoder-decoder model into separate ONNX graphs. The low-level
    # export() takes (model, OnnxConfig, output) and has no model_name_or_path/task.
    from optimum.exporters.onnx import main_export
    from transformers import WhisperProcessor

    output_dir.mkdir(parents=True, exist_ok=True)
    processor = WhisperProcessor.from_pretrained(model.config._name_or_path)

    # The on-device decoder loop (OnnxRuntimeHolder.runDecoderStep) feeds exactly
    # input_ids + encoder_hidden_states. A KV-cache export additionally demands
    # past_key_values.* / use_cache_branch inputs, which ORT would reject at runtime
    # on the phone — so export the cacheless variant.
    model.config.use_cache = False
    if getattr(model, "generation_config", None) is not None:
        model.generation_config.use_cache = False

    with tempfile.TemporaryDirectory() as tmp:
        checkpoint = Path(tmp) / "merged"
        model.save_pretrained(checkpoint)
        processor.save_pretrained(checkpoint)

        logger.info("Exporting ONNX via optimum main_export → %s", output_dir)
        main_export(
            model_name_or_path=str(checkpoint),
            output=output_dir,
            task="automatic-speech-recognition",
            no_post_process=True,  # skip decoder_model_merged.onnx (needs use_cache_branch)
        )

    # optimum may nest outputs in an onnx/ subfolder depending on version
    alt = output_dir / "onnx"
    if alt.is_dir():
        for f in alt.glob("*.onnx*"):
            shutil.move(str(f), str(output_dir / f.name))

    encoder = output_dir / "encoder_model.onnx"
    if not encoder.is_file():
        raise FileNotFoundError(f"ONNX export produced no encoder_model.onnx in {output_dir}")

    # Prefer the plain cacheless decoder; fall back to whatever decoder graph exists
    # so a version change surfaces as a clear name rather than a missing-file error.
    decoder = next(
        (
            output_dir / name
            for name in ("decoder_model.onnx", "decoder_model_merged.onnx")
            if (output_dir / name).is_file()
        ),
        None,
    )
    if decoder is None:
        produced = sorted(p.name for p in output_dir.glob("*.onnx"))
        raise FileNotFoundError(f"ONNX export produced no decoder graph. Got: {produced}")
    logger.info("ONNX export produced encoder=%s decoder=%s", encoder.name, decoder.name)

    # Save processor artifacts for mobile tokenizer ids
    processor.save_pretrained(output_dir)
    return encoder, decoder


def quantize_dynamic(src: Path, dst: Path) -> Path:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    logger.info("Quantizing %s → %s", src.name, dst.name)
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QUInt8,
    )
    return dst


def wer_sanity_check(base_model_id: str, merged_model, sample_wav: Path | None) -> dict:
    """Optional WER regression on a short sample."""
    if sample_wav is None or not sample_wav.exists():
        return {"skipped": True, "reason": "no sample wav provided"}

    import torch
    import torchaudio
    from peft import PeftModel
    from transformers import WhisperForConditionalGeneration, WhisperProcessor

    processor = WhisperProcessor.from_pretrained(base_model_id)
    try:
        import soundfile as sf
        data, sr = sf.read(str(sample_wav), dtype="float32")
        wav = torch.from_numpy(data)
        if wav.ndim > 1:
            wav = wav.mean(dim=-1)
    except Exception:
        try:
            import av
            import numpy as np
            container = av.open(str(sample_wav))
            frames = [f.to_ndarray() for f in container.decode(audio=0)]
            raw = np.concatenate(frames, axis=1)
            sr = container.streams.audio[0].rate
            if raw.dtype != np.float32:
                raw = raw.astype(np.float32)
            wav = torch.from_numpy(raw)
            if wav.shape[0] > 1:
                wav = wav.mean(dim=0)
            else:
                wav = wav.squeeze(0)
        except Exception:
            import wave
            import numpy as np
            with wave.open(str(sample_wav), "rb") as wf:
                sr = wf.getframerate()
                raw = wf.readframes(wf.getnframes())
                data = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
                wav = torch.from_numpy(data)
    if sr != 16000:
        wav = torchaudio.functional.resample(wav.unsqueeze(0), sr, 16000).squeeze(0)
    inputs = processor(wav.squeeze().numpy(), sampling_rate=16000, return_tensors="pt")

    base = WhisperForConditionalGeneration.from_pretrained(base_model_id)
    with torch.no_grad():
        base_ids = base.generate(inputs.input_features, max_new_tokens=128)
    base_text = processor.batch_decode(base_ids, skip_special_tokens=True)[0]

    with torch.no_grad():
        merged_ids = merged_model.generate(inputs.input_features, max_new_tokens=128)
    merged_text = processor.batch_decode(merged_ids, skip_special_tokens=True)[0]

    return {
        "skipped": False,
        "base_text": base_text,
        "merged_text": merged_text,
        "outputs_differ": base_text.strip() != merged_text.strip(),
    }


def describe_model(model, base_model: str) -> dict:
    """
    Derive the runtime contract from the checkpoint rather than assuming Whisper-small.

    These values were hardcoded both here and in the Android code, so a model with
    different dimensions (large-v3 uses 128 mel bins) or different special-token ids
    would have been mis-tagged in the manifest and silently decoded as garbage on
    device. Everything the phone needs to run the graph is read from the model and
    its feature extractor.
    """
    from transformers import WhisperProcessor

    processor = WhisperProcessor.from_pretrained(base_model)
    fe = processor.feature_extractor
    cfg = model.config
    gen = getattr(model, "generation_config", None)

    def pick(*candidates, default=None):
        for value in candidates:
            if value is not None:
                return value
        return default

    n_samples = pick(getattr(fe, "n_samples", None), default=480000)
    hop = pick(getattr(fe, "hop_length", None), default=160)
    tok = processor.tokenizer

    def token_id(piece: str, fallback: int | None = None) -> int | None:
        try:
            ids = tok.convert_tokens_to_ids(piece)
            if isinstance(ids, int) and ids >= 0:
                return ids
        except Exception:
            pass
        return fallback

    return {
        "architecture": "whisper-encoder-decoder",
        "sample_rate": int(pick(getattr(fe, "sampling_rate", None), default=16000)),
        "n_mels": int(pick(getattr(fe, "feature_size", None), getattr(cfg, "num_mel_bins", None), default=80)),
        "n_fft": int(pick(getattr(fe, "n_fft", None), default=400)),
        "hop_length": int(hop),
        "n_frames": int(n_samples // hop),
        "mel_scale": "slaney",
        "log_normalization": {"type": "whisper", "clip_db": 8.0, "offset": 4.0, "divisor": 4.0},
        "vocab_size": int(getattr(cfg, "vocab_size", 0)) or None,
        "decoder_kv_cache": bool(getattr(cfg, "use_cache", False)),
        "io_names": {
            "encoder_input": "input_features",
            "encoder_output": "last_hidden_state",
            "decoder_input_ids": "input_ids",
            "decoder_encoder_hidden": "encoder_hidden_states",
        },
        "tokenizer": {"format": "hf_tokenizer_json", "byte_level_bpe": True},
        "decoding": {
            "strategy": "greedy",
            "max_new_tokens": int(pick(getattr(gen, "max_length", None), default=128)),
            "prompt_token_ids": [
                t for t in (
                    token_id("<|startoftranscript|>", 50258),
                    token_id("<|en|>", 50259),
                    token_id("<|transcribe|>", 50359),
                    token_id("<|notimestamps|>", 50363),
                ) if t is not None
            ],
            "eot_token_id": token_id("<|endoftext|>", 50257),
        },
    }


def build_bundle(output_dir: Path, adapter_id: str, use_int8: bool) -> Path:
    # Ship only the precision the manifest actually points at. Bundling the fp32
    # graphs alongside the int8 ones tripled the download (~900MB vs ~250MB) with
    # files the phone never opens.
    files = ["mobile_manifest.json", "tokenizer.json", "preprocessor_config.json"]
    if use_int8:
        files.extend(["encoder_model_int8.onnx", "decoder_model_int8.onnx"])
    else:
        files.extend(["encoder_model.onnx", "decoder_model.onnx"])

    zip_path = output_dir / "mobile_bundle.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name in files:
            p = output_dir / name
            if p.exists():
                zf.write(p, arcname=name)
    logger.info("Created %s (%.1f MB)", zip_path, zip_path.stat().st_size / 1e6)
    return zip_path


def main() -> None:
    parser = argparse.ArgumentParser(description="Export TORGO LoRA Whisper for mobile ONNX")
    parser.add_argument("--adapter-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--base-model", default="openai/whisper-small")
    parser.add_argument("--adapter-id", default=None)
    parser.add_argument("--quantize", action="store_true", help="Emit INT8 dynamic-quantized ONNX")
    parser.add_argument("--sample-wav", type=Path, default=None, help="Optional WAV for WER sanity")
    args = parser.parse_args()

    adapter_dir = args.adapter_dir.resolve()
    adapter_id = args.adapter_id or json.loads(
        (adapter_dir / "adapter_manifest.json").read_text()
    )["adapter_id"]
    output_dir = (args.output_dir or Path("ml/mobile_export") / adapter_id).resolve()
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    merged = load_merged_model(adapter_dir, args.base_model)
    encoder, decoder = export_onnx(merged, output_dir)

    # Copy tokenizer assets from adapter dir if export did not emit them
    for name in ("tokenizer.json", "tokenizer_config.json", "preprocessor_config.json"):
        src = adapter_dir / name
        if src.is_file() and not (output_dir / name).exists():
            shutil.copy2(src, output_dir / name)

    int8_paths = {}
    if args.quantize:
        int8_paths["encoder"] = quantize_dynamic(
            encoder, output_dir / "encoder_model_int8.onnx"
        )
        int8_paths["decoder"] = quantize_dynamic(
            decoder, output_dir / "decoder_model_int8.onnx"
        )

    sanity = wer_sanity_check(args.base_model, merged, args.sample_wav)

    enc_file = "encoder_model_int8.onnx" if args.quantize else "encoder_model.onnx"
    dec_file = "decoder_model_int8.onnx" if args.quantize else "decoder_model.onnx"
    manifest = {
        "adapter_id": adapter_id,
        "base_model": args.base_model,
        "format": "onnx",
        "merged_lora": True,
        "encoder_file": enc_file,
        "decoder_file": dec_file,
        **describe_model(merged, args.base_model),
        "checksums": {
            "encoder": sha256_file(int8_paths.get("encoder", encoder)),
            "decoder": sha256_file(int8_paths.get("decoder", decoder)),
        },
        "sanity_check": sanity,
    }
    (output_dir / "mobile_manifest.json").write_text(json.dumps(manifest, indent=2))

    build_bundle(output_dir, adapter_id, args.quantize)
    logger.info(
        "Done — bundle written to disk on THIS machine. Nothing has been sent to the phone yet; "
        "the phone must pull it from GET /v1/adapters/%s/mobile "
        "(Settings -> Download Voice Model, or the calibration polling flow).",
        adapter_id,
    )
    if sanity.get("outputs_differ"):
        logger.info("Sanity OK: merged model output differs from base-only")
    elif not sanity.get("skipped"):
        logger.warning("Sanity WARNING: merged output identical to base — verify LoRA was applied")


if __name__ == "__main__":
    main()
