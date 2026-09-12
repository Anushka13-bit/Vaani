# VaaniMitra Calibration Pipeline — End-to-End Specification & Operations

This document describes the complete 7-step calibration pipeline for VaaniMitra, detailing the phone recording, batched transfer, laptop fine-tune, ONNX mobile conversion, status polling, and on-device NNAPI inference swap.

---

## Architecture Overview

```
[Phone (Patient)]
   │
   ├─ Step 1: react-native-audio-recorder-player records each prompt (16kHz mono PCM WAV),
   │          CalibrationScreen.tsx uploads one sample at a time
   │
   ├─ Step 2: Per-sample multipart POST /v1/calibration/sessions/{id}/samples
   │          (DEFAULT_SAMPLE_COUNT=40 prompts per session, prompt_set_id="torgo_en_v1")
   │          to configurable Base URL (http://127.0.0.1:8000 via adb reverse / 10.0.2.2 on emulator)
   ▼
[Laptop Backend (FastAPI)]
   │
   ├─ Step 3: POST /calibrate saves files & manifest to ./sessions/{session_id}/
   │          Returns HTTP 202 immediately via BackgroundTasks (non-blocking)
   │
   ├─ Step 4: Local Fine-Tune (Mac M3 / MPS)
   │          • PYTORCH_ENABLE_MPS_FALLBACK=1
   │          • device = "mps" (or "cpu")
   │          • Warm-starts from base Whisper-small + merged TORGO adapter (flat, global — not cluster-selected)
   │          • Freezes encoder; trains decoder-only LoRA (r=4, alpha=8, q_proj/v_proj)
   │          • 4 epochs, step-by-step loss logging
   │          • Status progression: "queued" → "training" → "exporting" → "ready" (or "failed")
   │
   └─ Step 5: Convert for Mobile (sherpa-onnx)
              • HF checkpoint (LoRA merged) converted in-memory to OpenAI Whisper
                format (ml/hf_to_openai_whisper.py), since sherpa-onnx's exporter
                only accepts that format
              • sherpa-onnx KV-cache export (encoder.onnx, decoder.onnx, tokens.txt),
                vendored from k2-fsa/sherpa-onnx in ml/sherpa_whisper_export/
              • INT8 dynamic quantization (encoder.int8.onnx, decoder.int8.onnx)
              • Packages mobile_manifest.json + tokens.txt into mobile_bundle.zip
                (see docs/SHERPA_ONNX_CONTRACT.md for the full manifest schema)
   ▼
[Phone (Client Retrieval & Inference)]
   │
   ├─ Step 6: Polls GET /adapter/{session_id}/status every 3s
   │          • Tolerates 404 / "not ready" without crashing
   │          • When status == "ready", downloads mobile_bundle.zip via GET /adapter/{session_id}
   │
   └─ Step 7: Load on Phone
              • AdapterManager extracts bundle to files/whisper_models/{adapter_id}/
              • Switches WhisperInferenceEngine.activeAdapterId
              • Releases the prior cached sherpa-onnx OfflineRecognizer
              • Subsequent inference runs via sherpa-onnx's OfflineRecognizer
                (NNAPI requested, CPU fallback — see SherpaOnnxWhisperRuntime.kt)
```

---

## Important Operational Flags

### 1. Mac M3 Sleep Prevention (`caffeinate`)
On macOS (e.g., Apple Silicon M3), running long local fine-tuning jobs (which take 3–8 minutes) can get paused or interrupted if the laptop goes to sleep or displays dim.
To ensure uninterrupted training during calibration sessions or live demos, **run the backend using macOS `caffeinate`**:

```bash
# In training-backend/:
caffeinate -d -i -m -u uvicorn app.main:app --reload --port 8000
```
- `-d`: Prevents display from sleeping
- `-i`: Prevents system from idle sleeping
- `-m`: Prevents disk idle sleep
- `-u`: Simulates user activity while the process runs

### 2. Pre-Baked Demo Fallback Path
If local training fails (e.g., laptop offline, network drop, missing weights, or training timeout), the mobile app has an **automatic demo fallback**:
- In `CalibrationScreen.tsx`, upon timeout, 501, or status="failed", the app catches the condition and immediately triggers `loadClusterAdapter()`.
- `loadClusterAdapter()` downloads and activates the pre-baked TORGO adapter (`torgo_base_adapter_english_v1`) — a flat global adapter, not a cluster selection.
- **Prerequisite for Fallback**: Ensure `ml/mobile_export/torgo_base_adapter_english_v1/mobile_bundle.zip` is built and present on the server so the cluster fallback bundle can be served.

### 3. Qualcomm QNN Context Binary vs Android NNAPI
- **Current Mobile Runtime**: The Android app runs Whisper inference via **sherpa-onnx**'s `OfflineRecognizer` (a prebuilt AAR, k2-fsa/sherpa-onnx, Apache 2.0 — see `docs/SHERPA_ONNX_CONTRACT.md`), which requests the **NNAPI** execution provider internally and falls back to CPU automatically on any device/model where NNAPI init fails.
- **QNN Status**: Proprietary Qualcomm QNN context binary files (`.bin`/`.context` from Qualcomm Neural Processing SDK) require Qualcomm's proprietary target compilation toolchain tied to specific Snapdragon SoC revisions, and would require building sherpa-onnx from source against that SDK rather than using the prebuilt AAR. Out of scope for this pass — see `docs/QNN_INTEGRATION.md`.

---

## Detailed Step Checklist

| Step | Component | Requirement | Repo Status |
|---|---|---|---|
| **1. Storage on Phone** | `CalibrationScreen.tsx` (`react-native-audio-recorder-player`) | 16kHz mono PCM WAV per prompt, uploaded immediately after each recording. (A native `AudioRecord`-based batch recorder + `manifest.json` path existed but was dead code — nothing called it — and has been removed; the general-purpose `AudioCaptureManager` used by the runtime wake-word/dictation pipeline is unaffected.) | **Implemented (JS path) & Verified** |
| **2. Transfer Phone → Laptop** | `CalibrationScreen.tsx`, `trainingBackendClient.ts` | Per-sample multipart POST to `/v1/calibration/sessions/{id}/samples`, `DEFAULT_SAMPLE_COUNT=40` prompts per session (`prompt_set_id="torgo_en_v1"`), to configurable base URL (default `127.0.0.1:8000`). | **Implemented & Verified** |
| **3. Receive on Laptop** | `app/routers/calibrate.py` | `POST /calibrate` receives clips + manifest, saves to `./sessions/{session_id}/`, returns 202 immediately via `BackgroundTasks`. | **Implemented & Verified** |
| **4. Train Locally** | `ml/run_finetune.py`, `app/workers/train_worker.py` | `PYTORCH_ENABLE_MPS_FALLBACK=1`; `device = "mps"`; merges TORGO adapter; freezes encoder; LoRA r=4, alpha=8; logs step loss; writes `status.json`. | **Implemented & Verified** |
| **5. Convert for Mobile** | `ml/export_whisper_mobile.py`, `ml/hf_to_openai_whisper.py`, `ml/sherpa_whisper_export/` | Converts merged HF checkpoint to OpenAI format in-memory, exports sherpa-onnx KV-cache ONNX + tokens.txt, dynamic INT8 quantization, packages `mobile_bundle.zip`. | **Implemented & Verified** |
| **6. Retrieve on Phone** | `app/routers/session_adapter.py`, `CalibrationScreen.tsx` | `GET /adapter/{session_id}/status` & `GET /adapter/{session_id}`; polling handles 404 gracefully; downloads only when "ready". | **Implemented & Verified** |
| **7. Load on Phone** | `AdapterManager.kt`, `WhisperInferenceEngine.kt` | `loadOnnxAdapter` actively swaps `activeAdapterId` and releases the cached `SherpaOnnxWhisperRuntime` recognizer, ensuring inference uses the new model on NNAPI-requested/CPU-fallback. | **Implemented & Verified** |
