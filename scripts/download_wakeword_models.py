#!/usr/bin/env python3
"""
Fetch openWakeWord ONNX models into the Android assets folder.

Cross-platform replacement for download_wakeword_models.sh: the bash version
needs curl and a POSIX shell, which a stock Windows box has neither of in the
form the script expects. Uses only the standard library so it runs anywhere
Python does.

    python scripts/download_wakeword_models.py
"""
from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
REQUIRED = ["melspectrogram.onnx", "embedding_model.onnx"]
# Dev fallback until hey_lily.onnx is trained — see assets/README_WAKEWORD.md
FALLBACK = "hey_jarvis_v0.1.onnx"


def download(url: str, dest: Path) -> None:
    print(f"  {dest.name} <- {url}")
    try:
        with urllib.request.urlopen(url, timeout=120) as r, dest.open("wb") as f:
            f.write(r.read())
    except urllib.error.URLError as e:
        raise SystemExit(f"Failed to download {url}: {e}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Download openWakeWord models into Android assets")
    ap.add_argument("--assets-dir", type=Path, default=None)
    ap.add_argument("--force", action="store_true", help="Re-download files that already exist")
    args = ap.parse_args()

    assets = args.assets_dir or (
        Path(__file__).resolve().parent.parent
        / "mobile-app" / "android" / "app" / "src" / "main" / "assets"
    )
    assets.mkdir(parents=True, exist_ok=True)
    print(f"Assets directory: {assets}")

    for name in REQUIRED:
        dest = assets / name
        if dest.is_file() and not args.force:
            print(f"  {name} already present ({dest.stat().st_size / 1e6:.1f} MB)")
            continue
        download(f"{BASE_URL}/{name}", dest)

    if (assets / "hey_lily.onnx").is_file():
        print("  hey_lily.onnx present — skipping the hey_jarvis fallback")
    else:
        dest = assets / FALLBACK
        if dest.is_file() and not args.force:
            print(f"  {FALLBACK} already present")
        else:
            print(f"  hey_lily.onnx not trained yet — fetching {FALLBACK} as the wake word")
            download(f"{BASE_URL}/{FALLBACK}", dest)

    print("\nDone:")
    for f in sorted(assets.glob("*.onnx")):
        print(f"  {f.name:28} {f.stat().st_size / 1e6:>8.1f} MB")
    if not any(assets.glob("*.onnx")):
        sys.exit("No .onnx assets present — the wake word will not start.")


if __name__ == "__main__":
    main()
