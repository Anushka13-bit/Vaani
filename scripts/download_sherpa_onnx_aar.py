#!/usr/bin/env python3
"""
Fetch the pinned sherpa-onnx Android AAR into mobile-app/android/app/libs/.

sherpa-onnx (k2-fsa/sherpa-onnx, Apache 2.0) publishes no Maven artifact —
the only supported distribution is a prebuilt .aar attached to a GitHub
Release, or building from source. This mirrors download_wakeword_models.py:
a one-time fetch script for a binary build dependency that is not committed
to git (see .gitignore), rather than checking a 50MB file into history.

The exact version, and why it was pinned, is recorded in
docs/SHERPA_ONNX_CONTRACT.md — both the backend export pipeline and this
Android dependency must stay on the same tag, since sherpa-onnx's KV-cache
ONNX graph contract can change between releases.

    python scripts/download_sherpa_onnx_aar.py
"""
from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.error
import urllib.request
from pathlib import Path

VERSION = "1.13.8"
URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar"
# Computed from the actual file fetched for this pin — verified against a
# corrupt/truncated download the same way ModelBundleManager checksums the
# mobile bundle on-device, not a value copied from anywhere else.
SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description="Download the pinned sherpa-onnx Android AAR")
    ap.add_argument("--libs-dir", type=Path, default=None)
    ap.add_argument("--force", action="store_true", help="Re-download even if already present")
    args = ap.parse_args()

    libs_dir = args.libs_dir or (
        Path(__file__).resolve().parent.parent
        / "mobile-app" / "android" / "app" / "libs"
    )
    libs_dir.mkdir(parents=True, exist_ok=True)
    dest = libs_dir / f"sherpa-onnx-{VERSION}.aar"

    if dest.is_file() and not args.force:
        if sha256_of(dest) == SHA256:
            print(f"{dest.name} already present and verified ({dest.stat().st_size / 1e6:.1f} MB)")
            return
        print(f"{dest.name} present but checksum mismatch — re-downloading")

    print(f"  {dest.name} <- {URL}")
    try:
        with urllib.request.urlopen(URL, timeout=300) as r, dest.open("wb") as f:
            f.write(r.read())
    except urllib.error.URLError as e:
        sys.exit(f"Failed to download {URL}: {e}")

    actual = sha256_of(dest)
    if actual != SHA256:
        dest.unlink()
        sys.exit(
            f"Checksum mismatch for {dest.name}: expected {SHA256}, got {actual}. "
            "Download was corrupt or the pinned release asset changed — deleted, re-run to retry."
        )
    print(f"Done: {dest.name} ({dest.stat().st_size / 1e6:.1f} MB, checksum verified)")


if __name__ == "__main__":
    main()
