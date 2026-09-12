#!/usr/bin/env python3
"""
Install a mobile ONNX bundle directly onto a USB-connected phone.

The normal path is: phone polls the backend and downloads the bundle itself
(calibration flow, or Settings -> Download Voice Model). This script skips that
round trip and writes the extracted bundle straight into the app's private
storage over adb, which is faster to iterate on and needs no UI interaction.

  python ml/push_bundle_to_phone.py --adapter-id user_<uuid>

By default it installs under the adapter id the app falls back to when nothing
is persisted (see VoicePipeline.processSession), so the model is picked up with
no SharedPrefs changes. Pass --install-as to override.

Requires a debuggable (debug) build, since it relies on `adb run-as`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PACKAGE = "com.vaanimitra"
DEVICE_TMP = "/data/local/tmp/vaani_bundle_push"
DEFAULT_INSTALL_AS = "torgo_base_adapter_english_v1"


def run(cmd: list[str], check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, capture_output=capture, text=True)
    if check and result.returncode != 0:
        sys.exit(
            f"Command failed ({result.returncode}): {' '.join(cmd)}\n"
            f"{(result.stderr or result.stdout or '').strip()}"
        )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Push a mobile ONNX bundle to a USB-connected phone")
    parser.add_argument("--adapter-id", required=True, help="Adapter whose bundle to install, e.g. user_<uuid>")
    parser.add_argument(
        "--install-as",
        default=DEFAULT_INSTALL_AS,
        help=f"Adapter id to install under on the device (default: {DEFAULT_INSTALL_AS})",
    )
    parser.add_argument("--bundle", type=Path, default=None, help="Explicit path to mobile_bundle.zip")
    parser.add_argument("--adb", default="adb", help="Path to adb if not on PATH")
    parser.add_argument("--keep-app-running", action="store_true", help="Skip force-stopping the app")
    args = parser.parse_args()

    bundle = args.bundle or (
        Path(__file__).resolve().parent / "mobile_export" / args.adapter_id / "mobile_bundle.zip"
    )
    if not bundle.is_file():
        sys.exit(f"Bundle not found: {bundle}\nRun ml/export_whisper_mobile.py first.")

    adb = args.adb
    devices = run([adb, "devices"]).stdout.strip().splitlines()[1:]
    online = [d.split()[0] for d in devices if d.strip().endswith("device")]
    if not online:
        sys.exit("No device in 'device' state. Check `adb devices` and USB debugging.")
    print(f"Device: {online[0]}")
    print(f"Bundle: {bundle} ({bundle.stat().st_size / 1e6:.1f} MB)")

    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / "bundle"
        staged.mkdir()
        with zipfile.ZipFile(bundle) as zf:
            zf.extractall(staged)

        manifest_path = staged / "mobile_manifest.json"
        if not manifest_path.is_file():
            sys.exit("Bundle has no mobile_manifest.json — the app cannot load it.")
        manifest = json.loads(manifest_path.read_text())
        encoder, decoder = manifest.get("encoder_file"), manifest.get("decoder_file")
        for label, name in (("encoder_file", encoder), ("decoder_file", decoder)):
            if not name or not (staged / name).is_file():
                sys.exit(f"Manifest {label}={name!r} is missing from the bundle — refusing to install.")

        # Catch a corrupt export here rather than after pushing ~200MB, where it would
        # surface on-device as an opaque ORT_INVALID_PROTOBUF at inference time.
        checksums = manifest.get("checksums") or {}
        for key, name in (("encoder", encoder), ("decoder", decoder)):
            expected = checksums.get(key)
            if not expected:
                continue
            digest = hashlib.sha256((staged / name).read_bytes()).hexdigest()
            if f"sha256:{digest}".lower() != str(expected).lower():
                sys.exit(f"{name} failed checksum — bundle is corrupt. Re-run export_whisper_mobile.py.")
        print(f"Manifest OK: encoder={encoder} decoder={decoder} (checksums verified)")

        dest = f"files/whisper_models/{args.install_as}"
        run([adb, "shell", "rm", "-rf", DEVICE_TMP], check=False)
        run([adb, "shell", "mkdir", "-p", DEVICE_TMP])

        files = sorted(p for p in staged.iterdir() if p.is_file())
        for path in files:
            print(f"  push {path.name} ({path.stat().st_size / 1e6:.1f} MB)")
            run([adb, "push", str(path), f"{DEVICE_TMP}/{path.name}"], capture=True)

        # run-as starts in the app's data dir, so dest stays relative.
        run([adb, "shell", "run-as", PACKAGE, "rm", "-rf", dest], check=False)
        run([adb, "shell", "run-as", PACKAGE, "mkdir", "-p", dest])
        for path in files:
            run([adb, "shell", "run-as", PACKAGE, "cp", f"{DEVICE_TMP}/{path.name}", f"{dest}/{path.name}"])

        run([adb, "shell", "rm", "-rf", DEVICE_TMP], check=False)

        listing = run([adb, "shell", "run-as", PACKAGE, "ls", "-l", dest]).stdout
        print(f"\nInstalled at /data/data/{PACKAGE}/{dest}:\n{listing}")
        for name in (encoder, decoder, "mobile_manifest.json"):
            if name not in listing:
                sys.exit(f"Verification failed: {name} not present on device after copy.")

    if not args.keep_app_running:
        # ONNX sessions are cached in OnnxRuntimeHolder; restart so the new files are read.
        run([adb, "shell", "am", "force-stop", PACKAGE], check=False)
        print("App force-stopped so it reloads the new model on next launch.")

    print(
        f"\nDone — model is on the phone as '{args.install_as}'.\n"
        "Launch the app, say the wake word, then watch:\n"
        "  adb logcat -s VoicePipeline:V WhisperInferenceEngine:V OnnxRuntimeHolder:V"
    )


if __name__ == "__main__":
    main()
