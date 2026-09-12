#!/usr/bin/env python3
"""
Measure whether a candidate adapter is actually better than the one in use.

Without this the pipeline can produce a new adapter but cannot tell whether it
improved anything, so "self-improving" would mean "changes over time" rather
than "gets better over time". Promotion is gated on a measured reduction in
word error rate over held-out audio the adapter was not trained on.

  python ml/evaluate_adapter.py \
      --eval-manifest sessions/<id>/training/holdout.json \
      --candidate sessions/<id>/training/lora_adapter \
      --baseline  ml/adapters/torgo_base_adapter_english_v1 \
      --report    sessions/<id>/training/eval_report.json

Exit code is 0 when the candidate is promotable and 2 when it is not, so a
deployment script can gate on it directly.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from eval_metrics import corpus_error_rate

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def load_eval_set(manifest: Path) -> list[dict]:
    """
    Manifest is a JSON list of {"audio_path": ..., "reference": ...}.
    Kept as plain data so held-out calibration audio, corrections, and a curated
    regression set can all feed the same evaluator.
    """
    items = json.loads(manifest.read_text(encoding="utf-8"))
    if not isinstance(items, list) or not items:
        raise SystemExit(f"Eval manifest {manifest} is empty or not a list")
    missing = [i["audio_path"] for i in items if not Path(i["audio_path"]).is_file()]
    if missing:
        raise SystemExit(f"{len(missing)} eval audio file(s) missing, first: {missing[0]}")
    return items


def load_audio(path: str, target_sr: int = 16000):
    import numpy as np

    try:
        import soundfile as sf
        data, sr = sf.read(path, dtype="float32")
        if data.ndim > 1:
            data = data.mean(axis=-1)
    except Exception:
        import wave
        with wave.open(path, "rb") as wf:
            sr = wf.getframerate()
            raw = wf.readframes(wf.getnframes())
            data = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
            if wf.getnchannels() > 1:
                data = data.reshape(-1, wf.getnchannels()).mean(axis=-1)
    if sr != target_sr:
        import torchaudio, torch
        data = torchaudio.functional.resample(
            torch.from_numpy(data).unsqueeze(0), sr, target_sr
        ).squeeze(0).numpy()
    return data


def transcribe_all(adapter_dir: Path | None, base_model: str, items: list[dict]) -> list[str]:
    """Transcribe every eval clip. adapter_dir=None evaluates the base model alone."""
    import torch
    from transformers import WhisperForConditionalGeneration, WhisperProcessor

    processor = WhisperProcessor.from_pretrained(base_model)
    model = WhisperForConditionalGeneration.from_pretrained(base_model)
    if adapter_dir is not None:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, str(adapter_dir)).merge_and_unload()
    model.eval()

    device = (
        torch.device("cuda") if torch.cuda.is_available()
        else torch.device("mps") if torch.backends.mps.is_available()
        else torch.device("cpu")
    )
    model.to(device)
    logger.info("Transcribing %d clips on %s (adapter=%s)",
                len(items), device, adapter_dir.name if adapter_dir else "none")

    out = []
    for n, item in enumerate(items, 1):
        audio = load_audio(item["audio_path"])
        feats = processor(audio, sampling_rate=16000, return_tensors="pt").input_features.to(device)
        with torch.no_grad():
            ids = model.generate(feats, max_new_tokens=128)
        out.append(processor.batch_decode(ids, skip_special_tokens=True)[0].strip())
        if n % 5 == 0 or n == len(items):
            logger.info("  %d/%d", n, len(items))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="Gate adapter promotion on measured WER improvement")
    ap.add_argument("--eval-manifest", type=Path, required=True)
    ap.add_argument("--candidate", type=Path, required=True, help="Adapter dir under test")
    ap.add_argument("--baseline", type=Path, default=None,
                    help="Adapter to beat; omit to compare against the base model alone")
    ap.add_argument("--base-model", default="openai/whisper-small")
    ap.add_argument("--report", type=Path, default=None)
    ap.add_argument("--min-improvement", type=float, default=0.0,
                    help="Required absolute WER reduction, e.g. 0.02 for 2 points")
    args = ap.parse_args()

    items = load_eval_set(args.eval_manifest)
    refs = [i["reference"] for i in items]

    cand_hyp = transcribe_all(args.candidate, args.base_model, items)
    base_hyp = transcribe_all(args.baseline, args.base_model, items)

    cand_wer = corpus_error_rate(list(zip(refs, cand_hyp)))
    base_wer = corpus_error_rate(list(zip(refs, base_hyp)))
    cand_cer = corpus_error_rate(list(zip(refs, cand_hyp)), char=True)
    base_cer = corpus_error_rate(list(zip(refs, base_hyp)), char=True)

    improvement = base_wer.rate - cand_wer.rate
    promote = improvement >= args.min_improvement and cand_wer.rate <= base_wer.rate

    report = {
        "eval_samples": len(items),
        "base_model": args.base_model,
        "candidate": str(args.candidate),
        "baseline": str(args.baseline) if args.baseline else f"{args.base_model} (no adapter)",
        "candidate_wer": round(cand_wer.rate, 4),
        "baseline_wer": round(base_wer.rate, 4),
        "candidate_cer": round(cand_cer.rate, 4),
        "baseline_cer": round(base_cer.rate, 4),
        "absolute_wer_improvement": round(improvement, 4),
        "min_improvement_required": args.min_improvement,
        "promote": promote,
        "per_sample": [
            {"audio": i["audio_path"], "reference": r, "candidate": c, "baseline": b}
            for i, r, c, b in zip(items, refs, cand_hyp, base_hyp)
        ],
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
        logger.info("Report written to %s", args.report)

    logger.info("WER  candidate=%.4f  baseline=%.4f  improvement=%+.4f",
                cand_wer.rate, base_wer.rate, improvement)
    logger.info("CER  candidate=%.4f  baseline=%.4f", cand_cer.rate, base_cer.rate)
    if promote:
        logger.info("PROMOTE — candidate measurably better on held-out audio")
        sys.exit(0)
    logger.warning("REJECT — candidate did not beat the baseline; keeping the current adapter")
    sys.exit(2)


if __name__ == "__main__":
    main()
