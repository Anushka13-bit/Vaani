#!/usr/bin/env python3
"""
Generate the onnxruntime "shim" pair that lets openWakeWord and sherpa-onnx
coexist in the same APK.

The problem (see docs/SHERPA_ONNX_CONTRACT.md): sherpa-onnx bundles its own
libonnxruntime.so, and xyz.rementia:openwakeword transitively depends on
com.microsoft.onnxruntime:onnxruntime-android:1.18.0, which ALSO ships a
libonnxruntime.so. Both files share a name but are binary-incompatible —
each hard-requires an exact ELF symbol-version tag matching its own release
(confirmed with `llvm-readobj --version-info`: openWakeWord's JNI needs
"VERS_1.18.0", sherpa-onnx's needs whatever its own pinned release embeds).
Only one file named libonnxruntime.so can exist in the merged APK, and it
must be sherpa's (Whisper STT is the app's core feature) — so openWakeWord's
JNI needs a differently-named copy of the *official* 1.18.0 build, with its
own DT_NEEDED entry patched to match.

This script:
  1. Downloads the official onnxruntime-android AAR for the exact version
     openWakeWord's POM declares (Maven Central — a stable, official URL).
  2. Extracts arm64-v8a's libonnxruntime.so and libonnxruntime4j_jni.so.
  3. Byte-patches libonnxruntime.so's own DT_SONAME field (one occurrence of
     "libonnxruntime.so", same length as "libonnxruntimf.so") — without this,
     the file loads fine under its new on-disk name but bionic's linker still
     registers it internally under the OLD embedded soname, so a verneed
     check against the new name fails at runtime with "cannot find
     libonnxruntimf.so ... in DT_NEEDED list" even though the bytes are
     present. Confirmed by inspecting the dynamic table with
     `llvm-readobj --dynamic-table` before trusting this fix.
  4. Byte-patches libonnxruntime4j_jni.so's single "libonnxruntime.so"
     occurrence (its DT_NEEDED entry) to "libonnxruntimf.so" — same length,
     in place, nothing else in the file changes.
  5. Writes both into mobile-app/android/app/src/main/jniLibs/arm64-v8a/,
     which app/build.gradle's packagingOptions.pickFirsts prefers over the
     unmodified copy bundled inside openwakeword's own AAR.

    python scripts/generate_wakeword_onnxruntime_shim.py
"""
from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ONNXRUNTIME_VERSION = "1.18.0"  # must match openWakeWord's declared dependency
URL = (
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/"
    f"{ONNXRUNTIME_VERSION}/onnxruntime-android-{ONNXRUNTIME_VERSION}.aar"
)
OLD_NAME = "libonnxruntime.so"
NEW_NAME = "libonnxruntimf.so"
assert len(OLD_NAME) == len(NEW_NAME), "renamed .so must be byte-identical length"


def download_aar(dest: Path) -> None:
    print(f"  fetching {URL}")
    try:
        with urllib.request.urlopen(URL, timeout=120) as r, dest.open("wb") as f:
            f.write(r.read())
    except urllib.error.URLError as e:
        sys.exit(f"Failed to download {URL}: {e}")


def patch_dt_needed(data: bytes) -> bytes:
    old = OLD_NAME.encode("ascii")
    new = NEW_NAME.encode("ascii")
    count = data.count(old)
    if count != 1:
        sys.exit(
            f"Expected exactly 1 occurrence of {OLD_NAME!r} in libonnxruntime4j_jni.so, "
            f"found {count}. The onnxruntime-android release layout may have changed — "
            "re-verify with `llvm-readobj --version-info` before patching blindly."
        )
    idx = data.index(old)
    return data[:idx] + new + data[idx + len(old):]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jnilibs-dir", type=Path, default=None)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    jnilibs = args.jnilibs_dir or (
        Path(__file__).resolve().parent.parent
        / "mobile-app" / "android" / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
    )
    jnilibs.mkdir(parents=True, exist_ok=True)

    renamed_ort = jnilibs / NEW_NAME
    patched_jni = jnilibs / "libonnxruntime4j_jni.so"
    if renamed_ort.is_file() and patched_jni.is_file() and not args.force:
        print(f"{renamed_ort.name} and {patched_jni.name} already present — use --force to regenerate")
        return

    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        aar_path = Path(tmp) / "onnxruntime-android.aar"
        download_aar(aar_path)

        with zipfile.ZipFile(aar_path) as zf:
            ort_so = zf.read("jni/arm64-v8a/libonnxruntime.so")
            jni_so = zf.read("jni/arm64-v8a/libonnxruntime4j_jni.so")

    renamed_ort.write_bytes(patch_dt_needed(ort_so))
    print(f"  wrote {renamed_ort} ({len(ort_so) / 1e6:.1f} MB, DT_SONAME patched: {OLD_NAME} -> {NEW_NAME})")

    patched = patch_dt_needed(jni_so)
    patched_jni.write_bytes(patched)
    print(f"  wrote {patched_jni} ({len(patched) / 1e6:.1f} MB, DT_NEEDED patched: {OLD_NAME} -> {NEW_NAME})")

    print("\nDone. Rebuild the app — packagingOptions.pickFirsts in app/build.gradle")
    print("already prefers these project-provided files over openwakeword's own AAR copy.")


if __name__ == "__main__":
    main()
