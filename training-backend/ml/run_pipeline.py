#!/usr/bin/env python3
"""
Export -> evaluate -> deploy, in one command.

The three stages already existed as separate scripts, which meant remembering
their order and arguments and, in practice, skipping evaluation entirely. Here
deployment is reachable only through the evaluation gate: a candidate adapter
ships if it measurably beat the baseline on held-out audio, and otherwise the
model already on the phone is left alone.

  python ml/run_pipeline.py --adapter-dir sessions/<id>/training/lora_adapter

Works the same on Windows, macOS and Linux: no shell, no path separators
assembled by hand, and the adb executable is resolved rather than assumed.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def discover_adapter_dir() -> Path:
    """
    Find the trained adapter so the common case needs no path typed out.

    Training writes sessions/<id>/training/lora_adapter, and the session id is a
    uuid nobody remembers. With one adapter present it is used; with several the
    choice is the user's, so they are listed newest first rather than guessed at.
    """
    sessions = HERE.parent / "sessions"
    found = sorted(
        (p for p in sessions.glob("*/training/lora_adapter") if p.is_dir()),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    ) if sessions.is_dir() else []

    if not found:
        raise SystemExit(
            f"No trained adapter found under {sessions}.\n"
            "Run a calibration session first, or pass --adapter-dir explicitly."
        )
    if len(found) == 1:
        return found[0]

    lines = "\n".join(
        f"  --adapter-dir {p}"
        f"{'   <- most recent' if i == 0 else ''}"
        for i, p in enumerate(found)
    )
    raise SystemExit(
        f"{len(found)} trained adapters found. Choose one:\n{lines}"
    )


def run_step(name: str, cmd: list[str]) -> int:
    print(f"\n{'=' * 70}\n{name}\n{'=' * 70}")
    print("$ " + " ".join(str(c) for c in cmd))
    # No shell=True: on Windows that would mangle paths containing spaces, which
    # is most of them under C:\Users\...
    return subprocess.run(cmd, cwd=str(HERE.parent)).returncode


def resolve_adb(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    # shutil.which finds adb.exe on Windows and adb elsewhere.
    return shutil.which("adb")


def main() -> None:
    ap = argparse.ArgumentParser(description="Export, evaluate and deploy an adapter")
    ap.add_argument("--adapter-dir", type=Path, default=None,
                    help="Trained LoRA adapter directory; discovered from sessions/ when omitted")
    ap.add_argument("--adapter-id", default=None, help="Defaults to the adapter manifest's id")
    ap.add_argument("--output-dir", type=Path, default=None)
    ap.add_argument("--base-model", default=None, help="Defaults to the exporter's own default")
    ap.add_argument("--eval-manifest", type=Path, default=None,
                    help="Held-out set; defaults to holdout.json beside the adapter")
    ap.add_argument("--baseline", type=Path, default=None,
                    help="Adapter to beat; omit to compare against the base model")
    ap.add_argument("--min-improvement", type=float, default=0.0)
    ap.add_argument("--device", default=None, help="adb serial when several are attached")
    ap.add_argument("--adb", default=None)
    ap.add_argument("--install-as", default=None, help="Adapter id to install under on device")
    ap.add_argument("--no-quantize", action="store_true")
    ap.add_argument("--skip-export", action="store_true", help="Reuse an existing bundle")
    ap.add_argument("--skip-eval", action="store_true",
                    help="Deploy without measuring. Use only when no held-out audio exists")
    ap.add_argument("--skip-push", action="store_true")
    args = ap.parse_args()

    adapter_dir = (args.adapter_dir or discover_adapter_dir()).resolve()
    if not adapter_dir.is_dir():
        sys.exit(f"Adapter directory not found: {adapter_dir}")
    print(f"Adapter: {adapter_dir}")

    adapter_id = args.adapter_id
    if not adapter_id:
        man = adapter_dir / "adapter_manifest.json"
        if not man.is_file():
            sys.exit(f"No --adapter-id given and no adapter_manifest.json in {adapter_dir}")
        adapter_id = json.loads(man.read_text(encoding="utf-8"))["adapter_id"]
    out_dir = (args.output_dir or (HERE / "mobile_export" / adapter_id)).resolve()
    py = sys.executable  # the active interpreter, not whatever "python" resolves to

    # 1. Export
    if args.skip_export:
        print(f"Skipping export; expecting a bundle in {out_dir}")
    else:
        cmd = [py, str(HERE / "export_whisper_mobile.py"),
               "--adapter-dir", str(adapter_dir), "--adapter-id", adapter_id,
               "--output-dir", str(out_dir)]
        if args.base_model:
            cmd += ["--base-model", args.base_model]
        if not args.no_quantize:
            cmd.append("--quantize")
        if run_step("1/3  EXPORT  merge LoRA -> ONNX -> quantize -> bundle", cmd) != 0:
            sys.exit("Export failed — nothing deployed.")

    bundle = out_dir / "mobile_bundle.zip"
    if not bundle.is_file():
        sys.exit(f"No bundle at {bundle} — cannot continue.")
    print(f"\nBundle: {bundle} ({bundle.stat().st_size / 1e6:.1f} MB)")

    # 2. Evaluate — the gate
    eval_manifest = args.eval_manifest or (adapter_dir.parent / "holdout.json")
    if args.skip_eval:
        print("\nWARNING: evaluation skipped — deploying an unmeasured adapter.")
    elif not eval_manifest.is_file():
        sys.exit(
            f"No held-out set at {eval_manifest}.\n"
            "Training reserves one automatically (EVAL_HOLDOUT_FRACTION); an older\n"
            "session predates that. Pass --eval-manifest, or --skip-eval to deploy\n"
            "without knowing whether this adapter is any better."
        )
    else:
        cmd = [py, str(HERE / "evaluate_adapter.py"),
               "--eval-manifest", str(eval_manifest),
               "--candidate", str(adapter_dir),
               "--report", str(out_dir / "eval_report.json"),
               "--min-improvement", str(args.min_improvement)]
        if args.baseline:
            cmd += ["--baseline", str(args.baseline)]
        if args.base_model:
            cmd += ["--base-model", args.base_model]
        rc = run_step("2/3  EVALUATE  measure WER on held-out audio", cmd)
        if rc != 0:
            print(
                "\nNot deploying: the candidate did not beat the baseline.\n"
                f"See {out_dir / 'eval_report.json'} for the per-sample comparison.\n"
                "The model currently on the device is unchanged."
            )
            sys.exit(rc)

    # 3. Deploy
    if args.skip_push:
        print("\nSkipping deployment (--skip-push). Bundle is ready at:")
        print(f"  {bundle}")
        return
    adb = resolve_adb(args.adb)
    if not adb:
        sys.exit("adb not found on PATH — pass --adb C:\\path\\to\\adb.exe, or use --skip-push.")
    cmd = [py, str(HERE / "push_bundle_to_phone.py"),
           "--adapter-id", adapter_id, "--bundle", str(bundle), "--adb", adb]
    if args.device:
        cmd += ["--device", args.device]
    if args.install_as:
        cmd += ["--install-as", args.install_as]
    if run_step("3/3  DEPLOY  install the bundle on the phone", cmd) != 0:
        sys.exit("Deployment failed — the bundle is built but not on the device.")

    print("\n" + "=" * 70)
    print("Pipeline complete. Launch the app and say the wake word, then watch:")
    print("  adb logcat -s VoicePipeline:V OnnxRuntimeHolder:V AndroidIntentActions:V")
    print("=" * 70)


if __name__ == "__main__":
    main()
