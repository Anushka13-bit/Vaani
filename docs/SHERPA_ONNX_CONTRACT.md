# Sherpa-ONNX Whisper Migration — Shared Contract

This is the authoritative interface between the backend export pipeline
(`training-backend/ml/`) and the Android on-device runtime
(`mobile-app/android/app/src/main/java/com/vaanimitra/stt/`). Both sides are
being rebuilt by separate agents in parallel — **treat every field name below
as fixed** unless you discover it is factually wrong against the real
sherpa-onnx source, in which case update this file first and note why.

## Why this migration

The previous on-device STT (`OnnxWhisperRuntime.kt` + `MelSpectrogram.kt` +
`WhisperTokenizer.kt`) was a hand-rolled cacheless-decoder ONNX pipeline —
manual mel extraction, manual greedy decode loop, manual tokenizer decode.
It's being fully replaced by **sherpa-onnx** (k2-fsa/sherpa-onnx, Apache 2.0),
which does mel extraction, KV-cache decoding, and tokenizer decode internally
in tested C++/Kotlin. This is a full replacement, not an additive layer — the
old files should be deleted once the new path works, not kept as dead code.

## Confirmed facts (verify against pinned version before building — do not
## trust this document blindly if it's more than a few weeks stale)

- **No Maven artifact.** Distribution is either build-from-source or a
  prebuilt `.aar` attached to a GitHub Release at
  https://github.com/k2-fsa/sherpa-onnx/releases (e.g.
  `sherpa-onnx-1.13.8.aar`). Pin one specific release tag for both the AAR
  *and* the export script (`scripts/whisper/export-onnx.py` from that same
  tag) so the tensor contract between them can't drift. Record the pinned
  tag in both `docs/SHERPA_ONNX_CONTRACT.md` (this file) and in a code
  comment at the export script's call site.
  **PINNED: `v1.13.8`** (`sherpa-onnx-1.13.8.aar`, 50,129,134 bytes, published
  2026-09-10 — the top of the Releases page at the time this was pinned, and
  the exact version this doc already suggested as an example). Downloaded to
  `mobile-app/android/app/libs/sherpa-onnx-1.13.8.aar` and wired into
  `mobile-app/android/app/build.gradle` as
  `implementation(files("libs/sherpa-onnx-1.13.8.aar"))`. Its `jni/` folder
  covers `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, each with its own
  `libonnxruntime.so` — confirming the AAR bundles ONNX Runtime itself, which
  is why the direct `com.microsoft.onnxruntime:onnxruntime-android:1.17.3`
  Gradle dependency was removed rather than kept alongside it. AAR
  `minSdkVersion` is 21, compatible with this app's minSdk 23.
  **Backend agent: export against this same `v1.13.8` tag.**
- Sherpa-onnx's Whisper decoder is a **KV-cache single-step decoder** — this
  is a fundamentally different ONNX graph shape than the old cacheless
  decoder. The C++ runtime does its own greedy-search decode loop
  internally (Whisper support is greedy-only upstream as of research date
  2026-09; no beam search).
- Sherpa-onnx's Whisper exporter loads models via `whisper.load_model()`
  from the **openai-whisper** pip package, which expects OpenAI's native
  checkpoint format (`{"dims": {...}, "model_state_dict": {...}}`), **not**
  HuggingFace `transformers` format. Our backend produces a merged HF
  `WhisperForConditionalGeneration` (LoRA merged via `peft.merge_and_unload()`).
  A conversion step (HF state_dict → OpenAI state_dict + dims) is required
  before export. `merge_and_unload()` folds LoRA into existing weight
  matrices with no new parameters, so the conversion mapping is standard
  Whisper-to-Whisper regardless of the adapter having been applied.
  Implemented in `training-backend/ml/hf_to_openai_whisper.py`, built as the
  exact inverse of HuggingFace's own `WHISPER_MAPPING` table in
  `transformers/models/whisper/convert_openai_to_hf.py` (fetched from
  huggingface/transformers @ main while writing this — that conversion
  script isn't shipped in the pip package, only in the git source tree).
  **Validated, not just assumed correct**: encoder output and teacher-forced
  decoder logits from the converted model are bit-for-bit identical (max abs
  diff `0.0`) to the original merged HF model on a real sample clip, and
  greedy-decoded text matches exactly — see
  `training-backend/ml/verify_hf_to_openai_conversion.py`.
- **Exact encoder tensor names/shapes** (from `AudioEncoderTensorCache` /
  `modified_audio_encoder_forward` in the pinned script): input `mel`
  `(n_audio, n_mels, T)` with dynamic axes on dims 0 and 2 (T need not be the
  full padded 3000 — the 30s assertion is removed). Outputs
  `n_layer_cross_k`, `n_layer_cross_v`, each
  `(n_text_layer, n_audio, n_audio_ctx, n_text_state)`.
- **Exact decoder tensor names/shapes** (from `TextDecoderTensorCache`):
  inputs `tokens` `(n_audio, n_tokens)`, `in_n_layer_self_k_cache` and
  `in_n_layer_self_v_cache` each
  `(n_text_layer, n_audio, n_text_ctx, n_text_state)`, `n_layer_cross_k` /
  `n_layer_cross_v` (from the encoder), and `offset` (int64 — how many tokens
  already decoded, used to slice the KV cache and positional embedding).
  Outputs: `logits`, `out_n_layer_self_k_cache`, `out_n_layer_self_v_cache`.
- **The C++ runtime reads its model config from ONNX metadata embedded in
  `encoder.onnx` itself, not from `mobile_manifest.json`.** The pinned
  script's `add_meta_data()` writes `model_type`, `n_mels`, all ten
  `ModelDimensions` fields, `sot_sequence`, `all_language_tokens`,
  `all_language_codes`, `sot`, `sot_index`, `eot`, `blank_id`,
  `is_multilingual`, `no_speech`, `non_speech_tokens`, `transcribe`,
  `translate`, `sot_prev`, `sot_lm`, `no_timestamps` as string-valued ONNX
  `metadata_props` directly on the encoder model proto — **not optional**,
  `OfflineWhisperModel` cannot build its tokenizer/decode config without it,
  regardless of what our JSON manifest says. Confirmed the metadata survives
  `quantize_dynamic` into `encoder.int8.onnx` unchanged (checked with
  `onnxruntime.InferenceSession` + `onnx.load` on the produced file).
  (Decoder gets no metadata in the pinned script except for `"large"`/`"turbo"`
  models, which also switch to ONNX external-data storage for the >2GB
  protobuf limit — not applicable to whisper-small.)
- **`tokens.txt` is not derived from our fine-tuned model or the HF
  tokenizer.** The pinned script's `convert_tokens()` copies
  `whisper/assets/multilingual.tiktoken` (or `gpt2.tiktoken` for
  English-only models) — a file bundled inside the installed
  `openai-whisper` pip package — verbatim, one `"<token> <rank>\n"` line per
  entry, where `<token>` is the raw tiktoken BPE token exactly as stored in
  that asset file (**not** base64-decoded — the script's own decode branch
  is dead code behind `if False:`). Fine-tuning never changes vocabulary, so
  this stock file is authoritative for any whisper-small-based adapter.
- **Int8 quantization**: `onnxruntime.quantization.quantize_dynamic(...,
  op_types_to_quantize=["MatMul"], weight_type=QuantType.QInt8)` — `QInt8`,
  **not** `QUInt8`, restricted to `MatMul` ops. This doc's own earlier draft
  and the old `optimum`-era export code both assumed `QUInt8` with no op-type
  restriction — wrong; corrected here after reading the real source.
- **Opset 17**, traced via the legacy (non-dynamo) `torch.onnx.export` path
  (pass `dynamo=False` — needed on torch>=2.5, where dynamo-based export is
  now the default and requires the optional `onnxscript` package; the
  vendored script's `dynamic_axes` kwarg is pre-dynamo TorchScript-exporter
  API). Tracing also needs `whisper.model.disable_sdpa()` around the whole
  export (upstream's own `if __name__ == "__main__":` block does the same,
  citing k2-fsa/sherpa-onnx#1764 — PyTorch's `scaled_dot_product_attention`
  can't trace an `is_causal` argument that is a Tensor rather than a `bool`).
- License: sherpa-onnx is Apache 2.0. Export logic is vendored (not
  pip-installed) into `training-backend/ml/sherpa_whisper_export/export.py`,
  adapted to accept an in-memory `whisper.model.Whisper` instance instead of
  upstream's fixed-choice `--model` CLI/file-path loader — see that file's
  module docstring for the itemized list of what changed vs. the pinned
  source and why, and a comment citing the exact source URL/tag/license.
- **No confidence/score signal exists in sherpa-onnx's ASR result — confirmed
  against the real source at `v1.13.8`, not assumed.**
  `sherpa-onnx/kotlin-api/OfflineRecognizer.kt`'s `OfflineRecognizerResult`
  is `{ text, tokens: Array<String>, timestamps: FloatArray, lang, emotion,
  event, durations: FloatArray }` — no log-prob, no score. The only
  `confidence` field anywhere in `sherpa-onnx/c-api/c-api.h` at this tag
  belongs to `SherpaOnnxOfflineSpeakerDiarizationSegment` (a distance-based
  cluster-assignment confidence for speaker diarization), which is an
  unrelated feature — not Whisper ASR. The old runtime's confidence (`exp(avg
  log-prob))` came from log-probs computed in the app's own manual decode
  loop; sherpa-onnx's greedy-search decode runs entirely in C++ and does not
  surface per-token or per-utterance scores back to Kotlin. Android's
  `TranscriptionResult.avgLogProb` is now always `null`, and per-segment
  `confidence` falls back to `ConfidenceScorer`'s existing text-length
  heuristic (already in the codebase as its "stub mode" path for when
  `tokenLogProbs` is null) — an honest placeholder, not a rediscovered real
  signal. If real confidence is needed later, the two options are: (a) a
  custom sherpa-onnx C++ build that exposes decode-time logits, or (b) a
  post-hoc encoder forward pass to score the emitted tokens — both out of
  scope for this migration.
- **`provider = "nnapi"` is fine to set and costs nothing on unsupported
  devices** — confirmed against `sherpa-onnx/csrc/session.cc` at `v1.13.8`:
  the C++ layer calls `OrtSessionOptionsAppendExecutionProvider_Nnapi()`
  inside a try/catch-equivalent status check and logs a warning + falls back
  to CPU silently on failure (never throws to Kotlin). QNN is a real
  `provider` value too, but requires `libQnnHtp.so`/`libQnnSystem.so` copied
  into `jniLibs/arm64-v8a` and a QNN-context binary per model
  (`sherpa-onnx/kotlin-api/QnnConfig.kt`) — that needs the Qualcomm SDK and
  is out of scope here (tracked in `docs/QNN_INTEGRATION.md`). Neither
  `OfflineRecognizer` nor `OfflineRecognizerResult` reports which EP actually
  executed a given node — there is no Kotlin-side equivalent of the old
  `OnnxRuntimeHolder`'s ORT-profiling EP-placement report — so
  `SherpaOnnxWhisperRuntime` reports `"nnapi-requested"` or `"cpu"`,
  reflecting only what was asked for, exactly as the old runtime's
  `"NNAPI-requested"` label did before profiling was wired up.
- **openWakeWord and sherpa-onnx cannot both bundle a plain `libonnxruntime.so`
  — confirmed as a real crash on real hardware, not a hypothetical.**
  `xyz.rementia:openwakeword:0.1.5` transitively depends on
  `com.microsoft.onnxruntime:onnxruntime-android:1.18.0`, which also ships a
  `libonnxruntime.so`. Removing the app's own direct `onnxruntime-android`
  dependency (since sherpa-onnx's AAR bundles its own) left sherpa's copy as
  the only one packaged — and openWakeWord's JNI immediately failed with
  `UnsatisfiedLinkError: cannot locate symbol OrtGetApiBase` the moment the
  wake-word service started, crashing the whole app. `llvm-readobj
  --version-info` on both `.so` files showed why: each hard-requires an
  **exact** ELF symbol-version tag matching its own release (openWakeWord
  needs `VERS_1.18.0`, sherpa-onnx only defines `VERS_1.28.2`) — bionic's
  linker does exact-string verneed/verdef matching, so neither file can
  substitute for the other. Fixed by `scripts/generate_wakeword_onnxruntime_shim.py`,
  which fetches the official 1.18.0 build and produces a renamed,
  byte-patched copy (own `DT_SONAME` patched, and openWakeWord's JNI
  `DT_NEEDED` entry patched to match) so both coexist under different
  on-disk names. Verified end-to-end on the target Snapdragon 8 Elite Gen 5
  device: app no longer crashes, and the wake-word service's `AudioRecord`
  starts successfully.

## Manifest contract (`mobile_manifest.json`)

Backend writes this; Android's `ModelBundleManager.kt` reads it. All values
must be **derived from the actual model/processor config**, never hardcoded
literals duplicated between languages — this mirrors the existing
`describe_model()` philosophy already in `export_whisper_mobile.py`.

```json
{
  "adapter_id": "string",
  "base_model": "openai/whisper-small",
  "format": "sherpa-onnx-whisper-kv-cache",
  "sherpa_onnx_version": "1.13.8",
  "merged_lora": true,
  "quantized": true,
  "encoder_file": "encoder.int8.onnx",
  "decoder_file": "decoder.int8.onnx",
  "tokens_file": "tokens.txt",
  "language": "en",
  "task": "transcribe",
  "sample_rate": 16000,
  "n_mels": 80,
  "checksums": {
    "encoder": "sha256:...",
    "decoder": "sha256:...",
    "tokens": "sha256:..."
  },
  "sanity_check": { "...": "as today — before/after text differs from base" }
}
```

Old-format bundles (`"format": "onnx"` or missing `format`) must be treated
as **incompatible**, not silently misread — same fail-loud philosophy as the
existing `MobileManifest.incompatibilityReason()`.

## Division of work

- **Backend agent** owns: `training-backend/ml/export_whisper_mobile.py`
  (rewritten to produce the above), a new HF→OpenAI state-dict conversion
  module, vendoring/adapting sherpa-onnx's encoder/decoder export wrapper
  classes, `run_finetune.py`'s live-export call site, and
  `push_bundle_to_phone.py`/bundle zip contents if the file list changed.
  Must actually run the export against a real local adapter
  (`training-backend/ml/adapters/torgo_base_adapter_english_v1/` or one of
  the `user_*` dirs) to prove it produces a loadable bundle, not just write
  code that looks right.
- **Android agent** owns: adding the sherpa-onnx AAR dependency, a new
  runtime class replacing `OnnxWhisperRuntime.kt`, updating
  `WhisperInferenceEngine.kt` and `ModelBundleManager.kt` for the new
  manifest schema, updating `AdapterManager.kt`'s execution-provider
  checks, and deleting the fully-superseded old files
  (`MelSpectrogram.kt`, `WhisperTokenizer.kt`, `OnnxRuntimeHolder.kt`,
  `OnnxWhisperRuntime.kt`) plus the now-unused
  `com.microsoft.onnxruntime:onnxruntime-android` Gradle dependency. Must
  get `./gradlew assembleDebug` to succeed.
