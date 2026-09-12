# Hey Barfi wake word (openWakeWord — free, no API key)

Uses [openWakeWord](https://github.com/dscripka/openWakeWord) via ONNX on-device. **No Picovoice account needed.**

## Quick setup

From repo root:

```bash
chmod +x scripts/download_wakeword_models.sh
./scripts/download_wakeword_models.sh
```

This downloads into this folder:
- `melspectrogram.onnx` (required)
- `embedding_model.onnx` (required)
- `hey_jarvis_v0.1.onnx` (temporary fallback for testing)

## Custom "Hey Barfi" model

Train with openWakeWord (Python):

```bash
pip install openwakeword
# Follow https://github.com/dscripka/openWakeWord#training-custom-models
# Export → copy hey_barfi.onnx here
```

Once `hey_barfi.onnx` exists, the app prefers it over `hey_jarvis_v0.1.onnx`.

## Notes

- Wake word runs on **CPU** (ONNX); Whisper runs on **NNAPI** separately.
- Models are ~2–3 MB total and are gitignored — run the download script after clone.
