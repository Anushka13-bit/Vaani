#!/usr/bin/env bash
# Download openWakeWord ONNX models into Android assets (no API key required).
set -euo pipefail

ASSETS_DIR="$(cd "$(dirname "$0")/.." && pwd)/mobile-app/android/app/src/main/assets"
BASE_URL="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

mkdir -p "$ASSETS_DIR"

echo "Downloading openWakeWord models to $ASSETS_DIR ..."

curl -fsSL "$BASE_URL/melspectrogram.onnx" -o "$ASSETS_DIR/melspectrogram.onnx"
curl -fsSL "$BASE_URL/embedding_model.onnx" -o "$ASSETS_DIR/embedding_model.onnx"

# Dev fallback until you train hey_lily.onnx (see mobile-app/android/app/src/main/assets/README_WAKEWORD.md)
if [[ ! -f "$ASSETS_DIR/hey_lily.onnx" ]]; then
  echo "Downloading hey_jarvis_v0.1.onnx as temporary fallback (replace with hey_lily.onnx after training)"
  curl -fsSL "$BASE_URL/hey_jarvis_v0.1.onnx" -o "$ASSETS_DIR/hey_jarvis_v0.1.onnx"
fi

echo "Done. Assets:"
ls -lh "$ASSETS_DIR"/*.onnx 2>/dev/null || true
