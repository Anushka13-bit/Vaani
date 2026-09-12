#!/usr/bin/env python3
"""
VaaniMitra — Export merged TORGO LoRA Whisper to a sherpa-onnx mobile bundle.

Usage (from training-backend/, with GPU optional):
  pip install -r ml/requirements-export.txt
  python ml/export_whisper_mobile.py \\
    --adapter-dir ml/adapters/torgo_base_adapter_english_v1 \\
    --output-dir ml/mobile_export/torgo_base_adapter_english_v1 \\
    --quantize

Produces:
  mobile_export/<adapter_id>/
    encoder.onnx                # merged LoRA baked in, KV-cache single-step graph
    decoder.onnx
    encoder.int8.onnx           # emitted when --quantize is passed
    decoder.int8.onnx
    tokens.txt                  # openai-whisper's own BPE vocab, sherpa-onnx line format
    mobile_manifest.json        # checksums, format tag, WER sanity report
    mobile_bundle.zip           # what the Android app downloads

The on-device runtime is sherpa-onnx (k2-fsa/sherpa-onnx), not a hand-rolled
ONNX Runtime pipeline. The exact tensor names/shapes, ONNX metadata schema,
tokens.txt format, and quantization args are vendored from sherpa-onnx's own
`scripts/whisper/export-onnx.py` into `ml/sherpa_whisper_export/` — see that
package and `docs/SHERPA_ONNX_CONTRACT.md` for the pinned version and the
full list of confirmed-vs-assumed facts. Because that exporter only accepts
models in the native `openai-whisper` checkpoint format, `ml/hf_to_openai_whisper.py`
converts our merged HF checkpoint (LoRA folded in via `peft.merge_and_unload()`)
in memory first — no intermediate files, no `optimum` ONNX export involved.
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

# Pinned sherpa-onnx release tag — must match the AAR the Android side bundles
# and the export-onnx.py revision vendored into ml/sherpa_whisper_export/.
# See docs/SHERPA_ONNX_CONTRACT.md for why this tag was chosen.
SHERPA_ONNX_VERSION = "1.13.8"

BUNDLE_FORMAT = "sherpa-onnx-whisper-kv-cache"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def load_merged_model(adapter_dir: Path, base_model: str):
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


def _sherpa_model_type(base_model: str) -> str:
    """
    Derive sherpa-onnx's `model_type` ONNX-metadata tag (e.g. "whisper-small")
    from the HF base model id, instead of hardcoding a whisper-small literal —
    this stays correct if the base model changes to medium/large-v3/etc.
    """
    slug = base_model.rsplit("/", 1)[-1]
    name = slug[len("whisper-"):] if slug.startswith("whisper-") else slug
    return f"whisper-{name}"


def export_sherpa_onnx(model, output_dir: Path, base_model: str, quantize: bool) -> dict[str, Path]:
    """
    Merged HF Whisper -> OpenAI Whisper checkpoint format -> sherpa-onnx
    KV-cache encoder/decoder ONNX graphs + tokens.txt.

    This replaces the old `optimum.exporters.onnx.main_export` cacheless
    export entirely — sherpa-onnx's C++ runtime does its own greedy KV-cache
    decode loop and expects this specific graph shape, which `optimum` cannot
    produce.
    """
    from ml.hf_to_openai_whisper import convert_hf_to_openai_whisper
    from ml.sherpa_whisper_export import export_whisper_onnx

    logger.info("Converting merged HF model -> OpenAI Whisper checkpoint format")
    openai_model = convert_hf_to_openai_whisper(model)

    logger.info("Exporting sherpa-onnx KV-cache ONNX graphs -> %s", output_dir)
    return export_whisper_onnx(
        openai_model,
        output_dir,
        model_type=_sherpa_model_type(base_model),
        quantize=quantize,
    )


def wer_sanity_check(base_model_id: str, merged_model, sample_wav: Path | None) -> dict:
    """Optional WER regression on a short sample — unaffected by the export
    format change, since it compares HF `.generate()` output before/after
    merging LoRA, not anything ONNX-related."""
    if sample_wav is None or not sample_wav.exists():
        return {"skipped": True, "reason": "no sample wav provided"}

    import torch
    import torchaudio
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


def describe_bundle(model, base_model: str, adapter_manifest: dict) -> dict:
    """
    Derive the sherpa-onnx manifest's language/task/sample_rate/n_mels fields
    from the actual processor/config and the adapter's own manifest — never
    hardcoded literals duplicated between backend and Android. The rest of
    the runtime contract (special-token ids, decode strategy, dims) now lives
    as ONNX metadata embedded directly in encoder.onnx by
    ml/sherpa_whisper_export/export.py, since that's what sherpa-onnx's C++
    runtime actually reads at load time — not this JSON file.
    """
    from transformers import WhisperProcessor

    processor = WhisperProcessor.from_pretrained(base_model)
    fe = processor.feature_extractor
    tok = processor.tokenizer
    gen = getattr(model, "generation_config", None)

    task = "transcribe"
    forced = list(getattr(gen, "forced_decoder_ids", None) or [])
    for _, token_id in forced:
        if token_id is None:
            continue
        piece = tok.convert_ids_to_tokens(token_id)
        if piece in ("<|transcribe|>", "<|translate|>"):
            task = piece.strip("<|>")
            break

    language = adapter_manifest.get("language_code") or "en"

    return {
        "language": language,
        "task": task,
        "sample_rate": int(fe.sampling_rate),
        "n_mels": int(fe.feature_size),
    }


def build_bundle(output_dir: Path, use_int8: bool) -> Path:
    # Ship only the precision the manifest actually points at, plus tokens.txt
    # (required by sherpa-onnx's tokenizer) and the manifest itself. No HF
    # tokenizer/preprocessor JSON — sherpa-onnx needs neither; tokenization and
    # feature extraction happen inside its own C++ runtime.
    files = ["mobile_manifest.json", "tokens.txt"]
    if use_int8:
        files.extend(["encoder.int8.onnx", "decoder.int8.onnx"])
    else:
        files.extend(["encoder.onnx", "decoder.onnx"])

    zip_path = output_dir / "mobile_bundle.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name in files:
            p = output_dir / name
            if p.exists():
                zf.write(p, arcname=name)
    logger.info("Created %s (%.1f MB)", zip_path, zip_path.stat().st_size / 1e6)
    return zip_path


def export_mobile_bundle(
    adapter_dir: Path,
    output_dir: Path,
    base_model: str,
    adapter_id: str,
    quantize: bool,
    sample_wav: Path | None = None,
) -> Path:
    """
    Full pipeline shared by the CLI (`__main__` below) and the live
    per-user-calibration path (`run_finetune._export_mobile_bundle`), so the
    two can't drift into producing different bundle shapes.
    """
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    adapter_manifest = json.loads((adapter_dir / "adapter_manifest.json").read_text(encoding="utf-8"))

    merged = load_merged_model(adapter_dir, base_model)
    produced = export_sherpa_onnx(merged, output_dir, base_model, quantize)

    sanity = wer_sanity_check(base_model, merged, sample_wav)

    encoder = produced.get("encoder_int8") if quantize else produced["encoder"]
    decoder = produced.get("decoder_int8") if quantize else produced["decoder"]
    tokens = produced["tokens"]

    manifest = {
        "adapter_id": adapter_id,
        "base_model": base_model,
        "format": BUNDLE_FORMAT,
        "sherpa_onnx_version": SHERPA_ONNX_VERSION,
        "merged_lora": True,
        "quantized": bool(quantize),
        "encoder_file": encoder.name,
        "decoder_file": decoder.name,
        "tokens_file": tokens.name,
        **describe_bundle(merged, base_model, adapter_manifest),
        "checksums": {
            "encoder": sha256_file(encoder),
            "decoder": sha256_file(decoder),
            "tokens": sha256_file(tokens),
        },
        "sanity_check": sanity,
    }
    (output_dir / "mobile_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    build_bundle(output_dir, use_int8=quantize)

    if sanity.get("outputs_differ"):
        logger.info("Sanity OK: merged model output differs from base-only")
    elif not sanity.get("skipped"):
        logger.warning("Sanity WARNING: merged output identical to base — verify LoRA was applied")

    return output_dir / "mobile_bundle.zip"


def main() -> None:
    parser = argparse.ArgumentParser(description="Export TORGO LoRA Whisper for sherpa-onnx mobile bundle")
    parser.add_argument("--adapter-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--base-model", default="openai/whisper-small")
    parser.add_argument("--adapter-id", default=None)
    parser.add_argument("--quantize", action="store_true", help="Emit INT8 dynamic-quantized ONNX")
    parser.add_argument("--sample-wav", type=Path, default=None, help="Optional WAV for WER sanity")
    args = parser.parse_args()

    adapter_dir = args.adapter_dir.resolve()
    adapter_id = args.adapter_id or json.loads(
        (adapter_dir / "adapter_manifest.json").read_text(encoding="utf-8")
    )["adapter_id"]
    output_dir = (args.output_dir or Path("ml/mobile_export") / adapter_id).resolve()

    zip_path = export_mobile_bundle(
        adapter_dir=adapter_dir,
        output_dir=output_dir,
        base_model=args.base_model,
        adapter_id=adapter_id,
        quantize=args.quantize,
        sample_wav=args.sample_wav,
    )
    logger.info(
        "Done — bundle written to disk on THIS machine: %s. Nothing has been sent to the phone yet; "
        "the phone must pull it from GET /v1/adapters/%s/mobile "
        "(Settings -> Download Voice Model, or the calibration polling flow).",
        zip_path, adapter_id,
    )


if __name__ == "__main__":
    main()
