# QNN Execution Provider Integration — Requirements & Plan

## Status

**Not implemented.** The app currently runs ONNX Runtime Mobile with the **Android NNAPI**
execution provider (`OnnxRuntimeHolder.kt`, `opts.addNnapi()`), with automatic CPU fallback.
This is a real, on-device, non-cloud inference path — but it is not Qualcomm's QNN (AI Engine
Direct) execution provider, so it does not give the predictable, fully-utilized Hexagon NPU
access that "genuine on-device NPU inference" implies for judging purposes. See
[`docs/CALIBRATION_PIPELINE.md`](./CALIBRATION_PIPELINE.md) §3 and `README.md`'s limitations
table for where this was previously disclosed.

This document is deliberately **not code** — building QNN support requires Qualcomm's
proprietary SDK and testing against the actual iQOO 15 Hexagon NPU revision, neither of
which is available in this session. What follows is the concrete task list for whoever picks
this up with hardware access, plus why it doesn't fit cleanly into Red Light.

## Why NNAPI ≠ QNN

- **NNAPI** is Android's generic ML acceleration API. Qualcomm ships an NNAPI HAL driver that
  *can* delegate some ops to the Hexagon NPU, so this isn't pure CPU — but op coverage is
  inconsistent, delegation decisions are opaque to the app, and Qualcomm doesn't tune this
  path as aggressively as its native one.
- **QNN** (Qualcomm AI Engine Direct SDK) talks to the Hexagon Tensor Processor (HTP) directly.
  It's what gives full, predictable NPU utilization and offline-compiled context binaries
  matched to the exact SoC revision.

## What's needed, concretely

### 1. Qualcomm AI Engine Direct SDK
- Download via Qualcomm Package Manager (QPM3) — requires a (free) Qualcomm ID and accepting
  the SDK license. Get the version matched to the iQOO 15's chipset generation; confirm the
  exact chipset and Hexagon/HTP version once the device is in hand (Snapdragon generation and
  HTP architecture version must match the compiled context binary exactly — this is the #1
  failure mode: a context binary built for the wrong HTP version will not load).
- The SDK provides `libQnnHtp.so`, per-arch `libQnnHtpV<N>Stub.so`, `libQnnHtpPrepare.so`,
  `libQnnSystem.so`, plus the `qnn-onnx-converter` / `qnn-context-binary-generator` CLI tools.

### 2. ONNX Runtime build with QNN EP
- Microsoft publishes a QNN-enabled Android package (`onnxruntime-qnn` on Maven) for common
  QNN SDK versions — check compatibility against the SDK version pulled in step 1 first.
- If no prebuilt matches, ORT must be built from source with `--use_qnn` against that exact
  SDK version (slow, needs the Android NDK + the QNN SDK on the build machine — this is a
  laptop-side build step, not something the phone can do).

### 3. Bundle the QNN shared libraries
- Add the `.so` files from step 1 to `mobile-app/android/app/src/main/jniLibs/arm64-v8a/`
  (QNN HTP libraries are arm64-only — confirm `abiFilters` in `build.gradle` are scoped
  accordingly, or size/build time balloons for architectures that can't use them anyway).
- Watch for `packagingOptions` conflicts if `onnxruntime-qnn` and the raw QNN SDK libs both
  ship the same `.so` name.

### 4. Generate QNN context binaries for each exported model
Two viable paths — pick one and use it consistently for both the TORGO base adapter and every
per-user export in `export_whisper_mobile.py`, or inference will silently use whatever's
present without matching QNN's expectations:
- **Offline, laptop-side**: run the exported INT8 ONNX (encoder + decoder) through
  `qnn-onnx-converter` then `qnn-context-binary-generator` on the laptop, producing a
  `.bin` context binary per model. This must target the iQOO 15's exact HTP arch — done
  wrong, it fails to load on-device, not gracefully, so this needs on-site testing against
  the real phone, not just the laptop.
- **On-device, ORT-generated**: use ONNX Runtime's QNN EP context-cache option
  (`ep.context_enable` session config) so ORT generates and caches the context binary the
  first time the model runs on-device. Slower first inference, but skips the separate CLI
  toolchain and sidesteps SDK/ORT version-matching issues from path 1 — likely the safer
  choice under hackathon time pressure.

### 5. Code changes (once the above exists)
- `OnnxRuntimeHolder.kt`: add a QNN registration attempt before the existing `addNnapi()`
  call, mirroring today's try/fallback structure — e.g. try QNN first (with
  `htp_performance_mode` / backend path provider options), fall back to NNAPI, fall back to
  CPU, logging which EP actually initialized at each step (the `executionProvider` field
  already threaded through `WhisperInferenceEngine` → RN bridge is the right place to surface
  this — no new plumbing needed there).
- Keep NNAPI as the fallback tier, not a replacement — if QNN context binary loading fails
  for any reason mid-demo, silently dropping to NNAPI/CPU rather than crashing matters more
  than winning the "genuine NPU" framing on every single request.

### 6. Verification
- Confirm via logs (not just absence of an exception) that the QNN EP actually initialized
  and is executing — the same "silent CPU fallback" risk flagged for the per-user adapter
  swap (see `AdapterManager.loadOnnxAdapterVerified` in `AdapterManager.kt`) applies here:
  QNN session creation can fail and fall through to NNAPI/CPU without an error surfacing
  unless it's explicitly logged and checked.

## Where this fits in the Red Light / Green Light timeline

This is **not a Red Light task**. Steps 1–4 need a laptop with the QNN SDK installed and,
critically, iterative testing against the **actual iQOO 15 hardware** to get the HTP arch
match right — that's Green Light (both devices) or, more realistically given the toolchain
setup time, a **grey/evaluation window** where there's no build-mode restriction at all.
Attempting this cold during Green Light's ~45% time budget is risky: SDK download/setup
alone (account creation, license acceptance, multi-GB download) can eat a meaningful chunk
of that window before any actual integration work starts.

**Recommendation**: if judged time is tight, treat this as a stretch goal after the
calibration→LoRA pipeline (stages 1–7) is demo-solid on NNAPI, not a blocker to it. NNAPI is
still genuinely on-device, non-cloud inference — the honest framing for a live demo is
"personalized on-device Whisper via ONNX Runtime, NPU-accelerated where the driver supports
it, with QNN integration as documented follow-up work," rather than overclaiming QNN before
it's actually wired and tested on the target chip.
