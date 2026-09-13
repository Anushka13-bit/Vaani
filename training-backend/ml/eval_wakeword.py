"""
Standalone honest-eval report for the exported hey_barfi.onnx classifier.

Runs the *exact* exported ONNX file (the one copied into the Android assets
folder) through onnxruntime -- not the in-memory PyTorch model -- so this
measures what the phone will actually run.

Reports:
  1. Held-out TEST split windows (same categories as training: TTS positives,
     TTS hard-negatives, silence/noise, and most of the real-speech negatives)
     -- unseen files, but same distribution as training.
  2. The one full session folder of real recorded speech
     (training-backend/sessions/test_session_f009ac7f) that was held out
     entirely from feature caching/training -- genuinely unseen real audio,
     used as negatives (none of these phrases are "Hey Barfi").
  3. A fresh batch of newly-synthesized "Hey Barfi" positives using
     voice/rate/volume/phrasing combinations that were NOT in the training
     grid, as a (still-synthetic, still same 3 voices) sanity check for
     generalization beyond the exact training grid.
"""
import os
import sys
import json
import glob
import numpy as np
import soundfile as sf
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(__file__))
from train_wakeword import (  # noqa: E402
    REPO_ROOT, TRAINING_BACKEND, WAKEWORD_DATA, ASSETS_DIR,
    MELSPEC_PATH, EMBEDDING_PATH, SR, HELD_OUT_REAL_SESSION,
    load_int16_mono, make_windows, build_feature_extractor,
)

MODEL_PATH = os.path.join(ASSETS_DIR, "hey_barfi.onnx")
THRESHOLD = 0.20  # matches WAKE_THRESHOLD in WakeWordForegroundService.kt


def score_clip(sess, af, path):
    x = load_int16_mono(path)
    windows = make_windows(x)
    scores = []
    for w in windows:
        e = af._get_embeddings(w)  # noqa: SLF001
        if e.shape[0] != 16:
            if e.shape[0] > 16:
                e = e[-16:]
            else:
                pad = np.zeros((16 - e.shape[0], e.shape[1]), dtype=e.dtype)
                e = np.concatenate([pad, e], axis=0)
        inp = e[None, ...].astype(np.float32)
        out = sess.run(None, {sess.get_inputs()[0].name: inp})[0]
        scores.append(float(out.reshape(-1)[0]))
    return max(scores) if scores else 0.0


def main():
    sess = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
    af = build_feature_extractor()

    report = {}

    # --- 1. Test split from cached features (reuse train_wakeword's cache) ---
    cache_path = os.path.join(WAKEWORD_DATA, "features_cache.npz")
    if os.path.exists(cache_path):
        data = np.load(cache_path, allow_pickle=True)
        from train_wakeword import split_category, collect_files
        files = collect_files()

        def test_windows(category):
            n_files = len(files[category])
            feats = data[category]
            file_ndx = data[category + "__file_ndx"]
            _, _, test = split_category(feats, file_ndx, n_files)
            return test

        pos_test = test_windows("positive")
        neg_test = np.concatenate([
            test_windows("negative_tts"),
            test_windows("negative_silence"),
            test_windows("negative_real"),
        ])

        def run_batch(X):
            scores = []
            for i in range(X.shape[0]):
                inp = X[i][None, ...].astype(np.float32)
                out = sess.run(None, {sess.get_inputs()[0].name: inp})[0]
                scores.append(float(out.reshape(-1)[0]))
            return np.array(scores)

        pos_scores = run_batch(pos_test)
        neg_scores = run_batch(neg_test)

        report["test_split"] = {
            "n_positive": int(len(pos_scores)),
            "n_negative": int(len(neg_scores)),
            "false_reject_rate": float((pos_scores < THRESHOLD).mean()) if len(pos_scores) else None,
            "false_accept_rate": float((neg_scores >= THRESHOLD).mean()) if len(neg_scores) else None,
            "positive_score_mean": float(pos_scores.mean()) if len(pos_scores) else None,
            "positive_score_min": float(pos_scores.min()) if len(pos_scores) else None,
            "negative_score_mean": float(neg_scores.mean()) if len(neg_scores) else None,
            "negative_score_max": float(neg_scores.max()) if len(neg_scores) else None,
        }

    # --- 2. Held-out real session audio (never used in training/val/test) ---
    session_dir = os.path.join(TRAINING_BACKEND, "sessions", HELD_OUT_REAL_SESSION)
    real_files = sorted(glob.glob(os.path.join(session_dir, "phrase_*.wav")))
    real_scores = [score_clip(sess, af, p) for p in real_files]
    real_scores = np.array(real_scores)
    report["held_out_real_session"] = {
        "session": HELD_OUT_REAL_SESSION,
        "n_clips": int(len(real_scores)),
        "false_accept_rate": float((real_scores >= THRESHOLD).mean()) if len(real_scores) else None,
        "score_mean": float(real_scores.mean()) if len(real_scores) else None,
        "score_max": float(real_scores.max()) if len(real_scores) else None,
        "note": "All clips are real recorded calibration phrases, none of which are 'Hey Barfi'. "
                "This entire session folder was excluded from feature caching/training.",
    }

    # --- 3. Fresh unseen-grid positive synth (generated here, not cached) ---
    fresh_dir = os.path.join(WAKEWORD_DATA, "raw", "positive_eval_unseen")
    fresh_files = sorted(glob.glob(os.path.join(fresh_dir, "*.wav")))
    if fresh_files:
        fresh_scores = np.array([score_clip(sess, af, p) for p in fresh_files])
        report["fresh_unseen_grid_positive"] = {
            "n_clips": int(len(fresh_scores)),
            "false_reject_rate": float((fresh_scores < THRESHOLD).mean()),
            "score_mean": float(fresh_scores.mean()),
            "score_min": float(fresh_scores.min()),
            "note": "Hey Barfi TTS clips synthesized with rate/volume/phrasing combinations "
                    "NOT present in the training grid (still same 3 SAPI voices).",
        }
    else:
        report["fresh_unseen_grid_positive"] = None

    print(json.dumps(report, indent=2))
    with open(os.path.join(WAKEWORD_DATA, "model_out", "eval_report.json"), "w") as f:
        json.dump(report, f, indent=2)


if __name__ == "__main__":
    main()
