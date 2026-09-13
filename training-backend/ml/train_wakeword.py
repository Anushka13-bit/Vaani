"""
Trains a custom "Hey Barfi" wake-word classifier for openWakeWord.

This is a *lightweight* substitute for openWakeWord's reference training pipeline
(which assumes Piper TTS via the separate `piper-sample-generator` repo, plus
multi-GB downloaded background-noise/RIR datasets and a GPU). None of that is
available/appropriate in this environment, so instead:

  - Positive samples ("Hey Barfi") are synthesized with Windows SAPI voices
    (see generate_positive_tts.ps1), sweeping rate/volume/phrasing for some
    acoustic diversity out of only 3 installed voices.
  - Hard-negative samples are phonetically-similar/partial-phrase text variants
    from openwakeword.data.generate_adversarial_texts, also synthesized via SAPI
    (see generate_negative_tts.ps1).
  - Additional negatives come from real recorded human speech already in this
    repo (training-backend/sessions/*/phrase_*.wav — unrelated calibration
    phrases) and synthetic silence/white-noise clips.
  - The frozen feature extractors (melspectrogram.onnx / embedding_model.onnx)
    used here are loaded directly from mobile-app's own asset copies, so the
    training feature space is guaranteed identical to what the phone feeds the
    classifier at inference time (not just architecturally similar).
  - The classifier itself uses openwakeword.train.Model's network definition,
    loss, and `export_model` — but a plain custom epoch loop is used instead of
    the reference `train_model`/`auto_train` methods, which are built around
    infinite streaming generators over huge (hours-long) datasets. For a
    dataset of this size, a standard shuffled-minibatch loop is simpler,
    equivalent, and much easier to reason about.

No claim is made that this matches the robustness of a model trained with the
full reference pipeline (thousands of TTS voices, real background noise/RIR
augmentation, tens of hours of negative audio). See README_WAKEWORD.md and the
printed eval report for honest numbers.
"""
import os
import sys
import glob
import json
import random
import copy
import warnings
from dataclasses import dataclass, field

import numpy as np
import soundfile as sf
import torch
from torch.utils.data import DataLoader, TensorDataset, WeightedRandomSampler

warnings.filterwarnings("ignore")

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TRAINING_BACKEND = os.path.join(REPO_ROOT, "training-backend")
WAKEWORD_DATA = os.path.join(TRAINING_BACKEND, "ml", "wakeword_data")
ASSETS_DIR = os.path.join(REPO_ROOT, "mobile-app", "android", "app", "src", "main", "assets")

MELSPEC_PATH = os.path.join(ASSETS_DIR, "melspectrogram.onnx")
EMBEDDING_PATH = os.path.join(ASSETS_DIR, "embedding_model.onnx")

SR = 16000
WIN_SEC = 2.0  # a 2.0s clip produces exactly 16 embedding frames == openWakeWord's input_shape[0]
WIN_LEN = int(WIN_SEC * SR)
HOP_SEC = 0.5
TRAILING_PAD_SEC = 0.2  # small silence after speech when padding short clips

SEED = 1234
random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)

FEATURES_CACHE = os.path.join(WAKEWORD_DATA, "features_cache.npz")
MODEL_OUT_DIR = os.path.join(WAKEWORD_DATA, "model_out")
os.makedirs(MODEL_OUT_DIR, exist_ok=True)


def log(*a):
    print(*a, flush=True)


# ---------------------------------------------------------------------------
# Data collection
# ---------------------------------------------------------------------------

REAL_SESSIONS_DIR = os.path.join(TRAINING_BACKEND, "sessions")
# One full session is held out entirely from train/val/test -- never touched
# during training -- so we have genuinely unseen real speech for final eval.
HELD_OUT_REAL_SESSION = "test_session_f009ac7f"


def collect_files():
    positive = sorted(glob.glob(os.path.join(WAKEWORD_DATA, "raw", "positive", "*.wav")))
    negative_tts = sorted(glob.glob(os.path.join(WAKEWORD_DATA, "raw", "negative_tts", "*.wav")))
    negative_silence = sorted(glob.glob(os.path.join(WAKEWORD_DATA, "raw", "negative_silence", "*.wav")))

    negative_real_train = []
    held_out_real = []
    for session_dir in sorted(glob.glob(os.path.join(REAL_SESSIONS_DIR, "*"))):
        name = os.path.basename(session_dir)
        wavs = sorted(glob.glob(os.path.join(session_dir, "phrase_*.wav")))
        if not wavs:
            continue
        if name == HELD_OUT_REAL_SESSION:
            held_out_real.extend(wavs)
        else:
            negative_real_train.extend(wavs)

    return {
        "positive": positive,
        "negative_tts": negative_tts,
        "negative_silence": negative_silence,
        "negative_real": negative_real_train,
        "held_out_real": held_out_real,
    }


# ---------------------------------------------------------------------------
# Windowing
# ---------------------------------------------------------------------------

def load_int16_mono(path):
    x, sr = sf.read(path, dtype="int16", always_2d=False)
    if x.ndim > 1:
        x = x[:, 0]
    if sr != SR:
        raise ValueError(f"{path} has sample rate {sr}, expected {SR}")
    return x


def make_windows(x: np.ndarray):
    """Splits/pads raw int16 audio into one or more WIN_LEN windows."""
    if len(x) <= WIN_LEN:
        pad_total = WIN_LEN - len(x)
        trailing = min(int(TRAILING_PAD_SEC * SR), pad_total)
        leading = pad_total - trailing
        y = np.concatenate([
            np.zeros(leading, dtype=np.int16),
            x,
            np.zeros(trailing, dtype=np.int16),
        ])
        return [y]
    else:
        hop = int(HOP_SEC * SR)
        windows = []
        start = 0
        while start + WIN_LEN <= len(x):
            windows.append(x[start:start + WIN_LEN])
            start += hop
        if (len(x) - WIN_LEN) % hop != 0:
            windows.append(x[-WIN_LEN:])
        return windows


# ---------------------------------------------------------------------------
# Feature extraction
# ---------------------------------------------------------------------------

def build_feature_extractor():
    from openwakeword.utils import AudioFeatures
    return AudioFeatures(
        melspec_model_path=MELSPEC_PATH,
        embedding_model_path=EMBEDDING_PATH,
        inference_framework="onnx",
    )


def embed_file(af, path):
    x = load_int16_mono(path)
    windows = make_windows(x)
    embeddings = []
    for w in windows:
        e = af._get_embeddings(w)  # noqa: SLF001 -- stable internal method, see utils.py
        if e.shape[0] != 16:
            # Guard against off-by-one framing on edge-case lengths; center-pad/crop to 16.
            if e.shape[0] > 16:
                e = e[-16:]
            else:
                pad = np.zeros((16 - e.shape[0], e.shape[1]), dtype=e.dtype)
                e = np.concatenate([pad, e], axis=0)
        embeddings.append(e.astype(np.float32))
    return embeddings


def extract_all_features(files_by_category, use_cache=True):
    if use_cache and os.path.exists(FEATURES_CACHE):
        log(f"Loading cached features from {FEATURES_CACHE}")
        data = np.load(FEATURES_CACHE, allow_pickle=True)
        return {k: data[k] for k in data.files}

    af = build_feature_extractor()

    out = {}
    for category, files in files_by_category.items():
        log(f"Extracting features for category '{category}' ({len(files)} files)...")
        feats = []
        file_ndx = []  # which source file each window came from, for group-aware splitting
        for i, path in enumerate(files):
            try:
                windows = embed_file(af, path)
            except Exception as exc:
                log(f"  skipping {path}: {exc}")
                continue
            for w in windows:
                feats.append(w)
                file_ndx.append(i)
        out[category] = np.array(feats, dtype=np.float32) if feats else np.zeros((0, 16, 96), dtype=np.float32)
        out[category + "__file_ndx"] = np.array(file_ndx, dtype=np.int64)
        out[category + "__files"] = np.array(files)

    np.savez(FEATURES_CACHE, **out)
    log(f"Cached features to {FEATURES_CACHE}")
    return out


# ---------------------------------------------------------------------------
# Train/val/test split (grouped by source file so windows from the same file
# never leak across splits)
# ---------------------------------------------------------------------------

def group_split(n_files, train=0.7, val=0.15, seed=SEED):
    idx = list(range(n_files))
    rng = random.Random(seed)
    rng.shuffle(idx)
    n_train = int(n_files * train)
    n_val = int(n_files * val)
    return set(idx[:n_train]), set(idx[n_train:n_train + n_val]), set(idx[n_train + n_val:])


def split_category(feats, file_ndx, n_files):
    train_files, val_files, test_files = group_split(n_files)
    train_mask = np.array([f in train_files for f in file_ndx])
    val_mask = np.array([f in val_files for f in file_ndx])
    test_mask = np.array([f in test_files for f in file_ndx])
    return feats[train_mask], feats[val_mask], feats[test_mask]


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------

def build_dataset(X, y):
    return TensorDataset(torch.from_numpy(X).float(), torch.from_numpy(y).float())


def evaluate(model, loader, device):
    model.eval()
    tp = fp = tn = fn = 0
    with torch.no_grad():
        for xb, yb in loader:
            xb, yb = xb.to(device), yb.to(device)
            preds = model(xb).squeeze(-1)
            pred_labels = (preds >= 0.5).float()
            tp += ((pred_labels == 1) & (yb == 1)).sum().item()
            fp += ((pred_labels == 1) & (yb == 0)).sum().item()
            tn += ((pred_labels == 0) & (yb == 0)).sum().item()
            fn += ((pred_labels == 0) & (yb == 1)).sum().item()
    n_pos = tp + fn
    n_neg = tn + fp
    recall = tp / n_pos if n_pos else float("nan")
    fa_rate = fp / n_neg if n_neg else float("nan")  # false-accept rate on negatives
    accuracy = (tp + tn) / (n_pos + n_neg) if (n_pos + n_neg) else float("nan")
    return {
        "tp": tp, "fp": fp, "tn": tn, "fn": fn,
        "recall": recall, "false_accept_rate": fa_rate, "accuracy": accuracy,
    }


def main():
    log("=" * 70)
    log("Hey Barfi wake-word training")
    log("=" * 70)

    files = collect_files()
    for k, v in files.items():
        log(f"  {k}: {len(v)} files")

    categorized = {k: v for k, v in files.items() if k != "held_out_real"}
    feats = extract_all_features(categorized, use_cache=True)

    splits = {}
    for category in categorized:
        n_files = len(files[category])
        tr, va, te = split_category(feats[category], feats[category + "__file_ndx"], n_files)
        splits[category] = {"train": tr, "val": va, "test": te}
        log(f"  {category}: train={len(tr)} val={len(va)} test={len(te)} windows")

    def stack(split_name, categories, label):
        arrs = [splits[c][split_name] for c in categories if len(splits[c][split_name])]
        if not arrs:
            return np.zeros((0, 16, 96), dtype=np.float32), np.zeros((0,), dtype=np.float32)
        X = np.concatenate(arrs, axis=0)
        y = np.full((X.shape[0],), label, dtype=np.float32)
        return X, y

    neg_categories = ["negative_tts", "negative_silence", "negative_real"]

    X_train_pos, y_train_pos = stack("train", ["positive"], 1.0)
    X_train_neg, y_train_neg = stack("train", neg_categories, 0.0)
    X_val_pos, y_val_pos = stack("val", ["positive"], 1.0)
    X_val_neg, y_val_neg = stack("val", neg_categories, 0.0)
    X_test_pos, y_test_pos = stack("test", ["positive"], 1.0)
    X_test_neg, y_test_neg = stack("test", neg_categories, 0.0)

    X_train = np.concatenate([X_train_pos, X_train_neg])
    y_train = np.concatenate([y_train_pos, y_train_neg])
    X_val = np.concatenate([X_val_pos, X_val_neg])
    y_val = np.concatenate([y_val_pos, y_val_neg])
    X_test = np.concatenate([X_test_pos, X_test_neg])
    y_test = np.concatenate([y_test_pos, y_test_neg])

    log(f"\nTrain windows: {len(X_train)} ({int(y_train.sum())} positive / {int((y_train == 0).sum())} negative)")
    log(f"Val windows:   {len(X_val)} ({int(y_val.sum())} positive / {int((y_val == 0).sum())} negative)")
    log(f"Test windows:  {len(X_test)} ({int(y_test.sum())} positive / {int((y_test == 0).sum())} negative)")

    # ---------------------------------------------------------------
    # Model
    # ---------------------------------------------------------------
    from openwakeword.train import Model as OWWModel

    device = torch.device("cpu")
    oww = OWWModel(n_classes=1, input_shape=(16, 96), model_type="dnn", layer_dim=64, n_blocks=1)
    net = oww.model.to(device)
    loss_fn = oww.loss  # torch.nn.functional.binary_cross_entropy

    optimizer = torch.optim.Adam(net.parameters(), lr=1e-3)

    train_ds = build_dataset(X_train, y_train)
    val_ds = build_dataset(X_val, y_val)
    test_ds = build_dataset(X_test, y_test)

    # Weighted sampler to counter the negative-heavy class imbalance in training
    n_pos = max(int(y_train.sum()), 1)
    n_neg = max(int((y_train == 0).sum()), 1)
    sample_weights = np.where(y_train == 1, 1.0 / n_pos, 1.0 / n_neg)
    sampler = WeightedRandomSampler(sample_weights, num_samples=len(sample_weights), replacement=True)

    train_loader = DataLoader(train_ds, batch_size=32, sampler=sampler)
    val_loader = DataLoader(val_ds, batch_size=64)
    test_loader = DataLoader(test_ds, batch_size=64)

    n_epochs = 60
    best_state = None
    best_score = -1.0
    history = []

    for epoch in range(1, n_epochs + 1):
        net.train()
        epoch_losses = []
        for xb, yb in train_loader:
            xb, yb = xb.to(device), yb.to(device)
            optimizer.zero_grad()
            preds = net(xb).squeeze(-1)
            loss = loss_fn(preds, yb)
            loss.backward()
            optimizer.step()
            epoch_losses.append(loss.item())

        val_metrics = evaluate(net, val_loader, device)
        # Selection score: prioritize low false-accept rate, then recall.
        score = val_metrics["recall"] - 2.0 * val_metrics["false_accept_rate"]
        history.append({"epoch": epoch, "loss": float(np.mean(epoch_losses)), **val_metrics})

        if score > best_score:
            best_score = score
            best_state = copy.deepcopy(net.state_dict())

        if epoch % 5 == 0 or epoch == 1:
            log(f"epoch {epoch:3d}  loss={np.mean(epoch_losses):.4f}  "
                f"val_recall={val_metrics['recall']:.3f}  val_fa_rate={val_metrics['false_accept_rate']:.4f}  "
                f"val_acc={val_metrics['accuracy']:.3f}")

    net.load_state_dict(best_state)
    log(f"\nLoaded best checkpoint by epoch score (recall - 2*false_accept_rate = {best_score:.3f})")

    test_metrics = evaluate(net, test_loader, device)
    log("\n--- Held-out TEST split (same categories, unseen files) ---")
    log(json.dumps(test_metrics, indent=2))

    # ---------------------------------------------------------------
    # Export
    # ---------------------------------------------------------------
    oww.model = net
    oww.export_model(net, "hey_barfi", MODEL_OUT_DIR)
    onnx_path = os.path.join(MODEL_OUT_DIR, "hey_barfi.onnx")

    # torch 2.x's default dynamo-based ONNX exporter writes weights above a small
    # threshold to a companion "<name>.onnx.data" file. The Android side loads the
    # asset as a single opened stream (see xyz.rementia:openwakeword's
    # OnnxModelRunner), so it must be one self-contained file -- merge the
    # external data back in and remove the sidecar.
    external_data_path = onnx_path + ".data"
    if os.path.exists(external_data_path):
        import onnx as onnx_pkg
        m = onnx_pkg.load(onnx_path, load_external_data=True)
        onnx_pkg.save_model(m, onnx_path, save_as_external_data=False)
        os.remove(external_data_path)
        log(f"Merged external weights back into a single file: {onnx_path}")

    log(f"\nExported ONNX model to {onnx_path} ({os.path.getsize(onnx_path)} bytes)")

    with open(os.path.join(MODEL_OUT_DIR, "training_report.json"), "w") as f:
        json.dump({
            "files": {k: len(v) for k, v in files.items()},
            "train_windows": {"total": len(X_train), "positive": int(y_train.sum()), "negative": int((y_train == 0).sum())},
            "val_windows": {"total": len(X_val), "positive": int(y_val.sum()), "negative": int((y_val == 0).sum())},
            "test_windows": {"total": len(X_test), "positive": int(y_test.sum()), "negative": int((y_test == 0).sum())},
            "test_metrics": test_metrics,
            "history": history,
            "best_epoch_score": best_score,
        }, f, indent=2)

    log("\nDone. Run eval_wakeword.py for the full honest eval report (including real held-out session audio).")


if __name__ == "__main__":
    main()
