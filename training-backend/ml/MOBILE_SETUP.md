# Mobile sherpa-onnx Setup (TORGO LoRA → Android)

Your trained LoRA weights in `ml/adapters/torgo_base_adapter_english_v1/` are **PEFT/safetensors**.
The phone needs a **sherpa-onnx KV-cache ONNX bundle** (LoRA baked in at export time).
The on-device runtime is [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(pinned tag in `docs/SHERPA_ONNX_CONTRACT.md`), not a hand-rolled ONNX Runtime
pipeline — it does mel extraction, KV-cache greedy decoding, and tokenizer
decode internally.

## Step 1 — Export mobile bundle (one-time)

```bash
cd training-backend
python -m venv .venv-export && source .venv-export/bin/activate   # Windows: .venv-export\Scripts\activate
pip install -r ml/requirements-export.txt

python ml/export_whisper_mobile.py \
  --adapter-dir ml/adapters/torgo_base_adapter_english_v1 \
  --output-dir ml/mobile_export/torgo_base_adapter_english_v1 \
  --quantize \
  --sample-wav path/to/test.wav   # optional WER sanity check
```

This creates:
```
ml/mobile_export/torgo_base_adapter_english_v1/
  encoder.onnx / encoder.int8.onnx   # KV-cache single-step encoder graph
  decoder.onnx / decoder.int8.onnx   # KV-cache single-step decoder graph
  tokens.txt                         # openai-whisper's own BPE vocab (sherpa-onnx format)
  mobile_manifest.json
  mobile_bundle.zip    ← served by GET /v1/adapters/torgo_base_adapter_english_v1/mobile
```

Check `mobile_manifest.json` → `sanity_check.outputs_differ` should be `true`.
Note: sherpa-onnx's runtime reads its decode config (special-token ids, dims,
etc.) from ONNX metadata embedded directly in `encoder.onnx`, not from
`mobile_manifest.json` — see `docs/SHERPA_ONNX_CONTRACT.md` for the full list
of embedded fields.

## Step 2 — Restart backend

```bash
uvicorn app.main:app --reload --port 8000
```

## Step 3 — Wake word models (openWakeWord, free)

```bash
chmod +x scripts/download_wakeword_models.sh
./scripts/download_wakeword_models.sh
```

Train custom **Hey Lily**: see `mobile-app/android/app/src/main/assets/README_WAKEWORD.md`

## Step 4 — Build & run app

```bash
cd mobile-app
npm run android
```

Complete calibration (or Settings → Download Voice Model). The app downloads `mobile_bundle.zip` and runs it through sherpa-onnx's `OfflineRecognizer` on **NNAPI** (NPU/GPU when available) with **CPU fallback** — see `docs/SHERPA_ONNX_CONTRACT.md` for exactly which execution providers are wired and how failures fall back.
