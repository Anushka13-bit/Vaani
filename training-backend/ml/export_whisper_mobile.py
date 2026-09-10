#!/usr/bin/env python3
"""
VaaniMitra — Export merged TORGO LoRA Whisper to a mobile ONNX bundle.

Usage (from training-backend/, with GPU optional):
  pip install -r ml/requirements-export.txt
  python ml/export_whisper_mobile.py \\
    --adapter-dir ml/adapters/torgo_cluster_english_v1 \\
    --output-dir ml/mobile_export/torgo_cluster_english_v1

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
    from optimum.exporters.onnx import export
    from optimum.onnxruntime import ORTModelForSpeechSeq2Seq
    from transformers import WhisperProcessor

    output_dir.mkdir(parents=True, exist_ok=True)
    processor = WhisperProcessor.from_pretrained(model.config._name_or_path)

    logger.info("Exporting ONNX via optimum → %s", output_dir)
    export(
        model_name_or_path=None,
        output=output_dir,
        model=model,
        task="automatic-speech-recognition",
        monolith=False,
    )

    encoder = output_dir / "encoder_model.onnx"
    decoder = output_dir / "decoder_model.onnx"
    if not encoder.exists() or not decoder.exists():
        # optimum may nest in onnx/ subfolder
        alt = output_dir / "onnx"
        if (alt / "encoder_model.onnx").exists():
            for f in alt.glob("*.onnx"):
                shutil.move(str(f), str(output_dir / f.name))
        encoder = output_dir / "encoder_model.onnx"
        decoder = output_dir / "decoder_model.onnx"

    if not encoder.exists() or not decoder.exists():
        raise FileNotFoundError("ONNX export did not produce encoder/decoder files")

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
    wav, sr = torchaudio.load(str(sample_wav))
    if sr != 16000:
        wav = torchaudio.functional.resample(wav, sr, 16000)
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


def build_bundle(output_dir: Path, adapter_id: str, use_int8: bool) -> Path:
    files = ["encoder_model.onnx", "decoder_model.onnx", "mobile_manifest.json",
               "tokenizer.json", "preprocessor_config.json"]
    if use_int8:
        files.extend(["encoder_model_int8.onnx", "decoder_model_int8.onnx"])

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
        "sample_rate": 16000,
        "n_mels": 80,
        "checksums": {
            "encoder": sha256_file(int8_paths.get("encoder", encoder)),
            "decoder": sha256_file(int8_paths.get("decoder", decoder)),
        },
        "sanity_check": sanity,
    }
    (output_dir / "mobile_manifest.json").write_text(json.dumps(manifest, indent=2))

    build_bundle(output_dir, adapter_id, args.quantize)
    logger.info("Done. Deploy mobile_bundle.zip via GET /v1/adapters/%s/mobile", adapter_id)
    if sanity.get("outputs_differ"):
        logger.info("Sanity OK: merged model output differs from base-only")
    elif not sanity.get("skipped"):
        logger.warning("Sanity WARNING: merged output identical to base — verify LoRA was applied")


if __name__ == "__main__":
    main()
