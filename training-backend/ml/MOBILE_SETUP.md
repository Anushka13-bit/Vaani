# Mobile ONNX Setup (TORGO LoRA → Android)

Your trained LoRA weights in `ml/adapters/torgo_cluster_english_v1/` are **PEFT/safetensors**.
The phone needs a **merged ONNX bundle** (LoRA baked in at export time).

## Step 1 — Export mobile bundle (one-time, on your Mac)

```bash
cd training-backend
python -m venv .venv-export && source .venv-export/bin/activate
pip install -r ml/requirements-export.txt

python ml/export_whisper_mobile.py \
  --adapter-dir ml/adapters/torgo_cluster_english_v1 \
  --output-dir ml/mobile_export/torgo_cluster_english_v1 \
  --quantize \
  --sample-wav path/to/test.wav   # optional WER sanity check
```

This creates:
```
ml/mobile_export/torgo_cluster_english_v1/
  encoder_model_int8.onnx
  decoder_model_int8.onnx
  mobile_manifest.json
  mobile_bundle.zip    ← served by GET /v1/adapters/torgo_cluster_english_v1/mobile
```

Check `mobile_manifest.json` → `sanity_check.outputs_differ` should be `true`.

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

Complete calibration (or Settings → Download Voice Model). The app downloads `mobile_bundle.zip` and runs ONNX on **NNAPI** (NPU/GPU when available) with **CPU fallback**.

## Execution providers

| Provider | When |
|----------|------|
| NNAPI | Default attempt (uses device NPU/GPU via Android NNAPI) |
| CPU | Fallback if NNAPI init fails |
| Google STT | Fallback only if ONNX bundle not downloaded |

True Qualcomm **QNN** context binaries are not wired in this pass — NNAPI is the Android-standard NPU path.
