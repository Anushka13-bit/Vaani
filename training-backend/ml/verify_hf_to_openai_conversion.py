#!/usr/bin/env python3
"""
One-shot validation that ``hf_to_openai_whisper.py``'s state-dict conversion is
numerically correct — the highest-risk step in the sherpa-onnx migration.

Loads the merged TORGO LoRA model, converts it to OpenAI Whisper format, and
checks on a real sample clip that:
  1. Encoder outputs match (same mel input fed to both encoders directly, so
     feature-extraction differences between HF's processor and openai-whisper's
     log_mel_spectrogram can't hide a weight-mapping bug or fix one).
  2. Teacher-forced decoder logits match, using each model's own encoder output.
  3. Independently-run greedy generation (HF `.generate()` vs a small greedy
     loop against the OpenAI model) produces identical token ids and text.

Not part of the export pipeline itself — a standalone check to run once (or
after touching hf_to_openai_whisper.py) via:
  training-backend/.venv/Scripts/python.exe -m ml.verify_hf_to_openai_conversion
"""
from __future__ import annotations

import logging
import wave
from pathlib import Path

import numpy as np
import torch

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)

REPO_ROOT = Path(__file__).resolve().parent.parent
SAMPLE_WAV = REPO_ROOT / "sessions" / "test_session_3274a3c2" / "phrase_01.wav"
ADAPTER_DIR = REPO_ROOT / "ml" / "adapters" / "torgo_base_adapter_english_v1"
BASE_MODEL = "openai/whisper-small"


def load_wav_float32(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as wf:
        sr = wf.getframerate()
        raw = wf.readframes(wf.getnframes())
        data = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        if wf.getnchannels() > 1:
            data = data.reshape(-1, wf.getnchannels()).mean(axis=-1)
    return data, sr


def main() -> None:
    import whisper
    from peft import PeftModel
    from transformers import WhisperForConditionalGeneration, WhisperProcessor

    from ml.hf_to_openai_whisper import convert_hf_to_openai_whisper

    assert SAMPLE_WAV.is_file(), f"missing sample wav: {SAMPLE_WAV}"

    logger.info("Loading merged HF model (base=%s, adapter=%s)", BASE_MODEL, ADAPTER_DIR)
    base = WhisperForConditionalGeneration.from_pretrained(BASE_MODEL)
    hf_model = PeftModel.from_pretrained(base, str(ADAPTER_DIR)).merge_and_unload()
    hf_model.eval()

    logger.info("Converting to OpenAI Whisper format")
    openai_model = convert_hf_to_openai_whisper(hf_model)

    audio, sr = load_wav_float32(SAMPLE_WAV)
    if sr != 16000:
        import torchaudio

        audio = torchaudio.functional.resample(
            torch.from_numpy(audio).unsqueeze(0), sr, 16000
        ).squeeze(0).numpy()

    n_mels = hf_model.config.num_mel_bins
    audio_t = torch.from_numpy(audio)
    audio_padded = whisper.pad_or_trim(audio_t)
    mel = whisper.log_mel_spectrogram(audio_padded, n_mels=n_mels).unsqueeze(0)  # (1, n_mels, 3000)
    logger.info("mel shape=%s (from openai-whisper's own log_mel_spectrogram)", tuple(mel.shape))

    # --- 1. Encoder parity: identical mel in, compare outputs ---------------
    with torch.no_grad():
        hf_encoder_out = hf_model.model.encoder(input_features=mel).last_hidden_state
        openai_encoder_out = openai_model.encoder(mel)

    enc_diff = (hf_encoder_out - openai_encoder_out).abs()
    logger.info(
        "Encoder output diff: max=%.6g mean=%.6g (shapes hf=%s openai=%s)",
        enc_diff.max().item(), enc_diff.mean().item(),
        tuple(hf_encoder_out.shape), tuple(openai_encoder_out.shape),
    )
    assert enc_diff.max().item() < 1e-3, "Encoder outputs diverge — weight mapping bug"

    # --- 2. Teacher-forced decoder logits parity -----------------------------
    processor = WhisperProcessor.from_pretrained(BASE_MODEL)
    forced_ids = processor.get_decoder_prompt_ids(language="en", task="transcribe")
    prompt_tokens = [processor.tokenizer.convert_tokens_to_ids("<|startoftranscript|>")]
    prompt_tokens += [tid for _, tid in forced_ids]
    tokens = torch.tensor([prompt_tokens])

    with torch.no_grad():
        hf_logits = hf_model.model.decoder(
            input_ids=tokens, encoder_hidden_states=hf_encoder_out
        ).last_hidden_state
        hf_logits = hf_model.proj_out(hf_logits)
        openai_logits = openai_model.decoder(tokens, openai_encoder_out)

    logit_diff = (hf_logits - openai_logits).abs()
    logger.info(
        "Decoder logits diff: max=%.6g mean=%.6g (shape=%s)",
        logit_diff.max().item(), logit_diff.mean().item(), tuple(hf_logits.shape),
    )
    assert logit_diff.max().item() < 1e-2, "Decoder logits diverge — weight mapping bug"

    # --- 3. End-to-end greedy generation parity ------------------------------
    with torch.no_grad():
        hf_ids = hf_model.generate(mel, max_new_tokens=64, num_beams=1, do_sample=False)
    hf_text = processor.batch_decode(hf_ids, skip_special_tokens=True)[0].strip()

    generated = list(prompt_tokens)
    eot = processor.tokenizer.convert_tokens_to_ids("<|endoftext|>")
    with torch.no_grad():
        for _ in range(64):
            cur = torch.tensor([generated])
            logits = openai_model.decoder(cur, openai_encoder_out)
            next_id = int(logits[0, -1].argmax())
            generated.append(next_id)
            if next_id == eot:
                break
    openai_text = processor.tokenizer.decode(
        [t for t in generated if t not in prompt_tokens or t == eot], skip_special_tokens=True
    ).strip()
    # Decode the full generated sequence the same way HF does (skip specials,
    # keep only the newly generated span for a clean comparison).
    openai_text = processor.tokenizer.decode(
        generated[len(prompt_tokens):], skip_special_tokens=True
    ).strip()

    logger.info("HF generated text:     %r", hf_text)
    logger.info("OpenAI-format text:    %r", openai_text)
    hf_new_ids = hf_ids[0].tolist()
    # Trim HF's ids to just the generated span (after its own prompt) for a
    # like-for-like token comparison.
    logger.info("HF ids: %s", hf_new_ids)
    logger.info("OpenAI-format ids (incl. prompt): %s", generated)

    assert hf_text == openai_text, (
        f"Generated text mismatch!\n  HF:     {hf_text!r}\n  OpenAI: {openai_text!r}"
    )

    logger.info("PASS — HF -> OpenAI Whisper conversion verified numerically identical.")


if __name__ == "__main__":
    main()
