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

A real one has already been trained — see `training-backend/ml/train_wakeword.py` (plus
`eval_wakeword.py` and the sample-generation scripts under `training-backend/ml/wakeword/`).
It trains openWakeWord's classifier head directly against the package's pretrained
melspectrogram/embedding feature extractors — **not** via openWakeWord's own reference
pipeline, which needs Piper TTS + a GPU + several GB of room-impulse-response/background
datasets we didn't have budget for this pass. Instead:
- **Positives**: Windows SAPI TTS across every installed voice, varied rate, saying "Hey Barfi".
- **Negatives**: SAPI-synthesized adversarial/partial phrases (`generate_adversarial_texts`)
  plus real recorded human speech from `training-backend/sessions/` (genuine dysarthric-adjacent
  TORGO-style speech saying unrelated phrases) plus silence.
- **Result**: 30/30 true positives, 0/114 false accepts on a held-out real-audio test set — see
  `training-backend/ml/wakeword_data/model_out/training_report.json` (regenerate via
  `python ml/train_wakeword.py`; the data/model_out directory itself is gitignored, regenerable).
  Small test set — a promising signal, not a large-scale robustness guarantee. If GPU/time budget
  allows later, re-running openWakeWord's full reference pipeline would be more robust.

Once `hey_barfi.onnx` exists (in this folder), the app prefers it over `hey_jarvis_v0.1.onnx`.

## Notes

- Wake word runs on **CPU** (ONNX); Whisper runs on **NNAPI** separately.
- Models are ~2–3 MB total and are gitignored — run the download script after clone.

## Known limitation: background listening on aggressive OEM skins (e.g. vivo/OriginOS)

Confirmed on real hardware (vivo I2501 / iQOO 15, OriginOS/Android 16): wake-word detection
works reliably while the app is in the foreground, but **stops within seconds once the
Activity loses visibility** (Home press, or swiping the app away from Recents) — even after
all of the following, none of which fixed it:
- `android:stopWithTask="false"` on `WakeWordForegroundService` (still worth keeping — it
  does fix the *separate* problem of the service being torn down when the task is removed,
  confirmed via `WakeWordEngine resumed after command session` logs continuing to appear
  after a Recents swipe-away, before this specific listening-stops-anyway issue was found).
- A held `PowerManager.PARTIAL_WAKE_LOCK` while listening (`WakeWordForegroundService`
  acquires one — confirmed held via `adb shell dumpsys power` — CPU sleep was not the cause).
- Battery-optimization whitelist (`adb shell dumpsys deviceidle whitelist +com.vaanimitra`).
- vivo's own **Settings → Battery → Background power control → Allow background power usage**
  (found via `com.iqoo.powersaving`; there is no adb-only path to this screen — it required a
  live UI search for "background power" in system Settings).
- vivo's **Settings → Apps → Special app access → Autostart** — already "Allowed" for VaaniMitra.
- Disabling Android's "Pause app activity if unused" (App info page) for VaaniMitra.

`adb shell dumpsys activity processes com.vaanimitra` shows the process correctly classified
`cur=FGS set=FGS mAdjType=fg-service-act cached=false` while backgrounded — by Android's own
process-importance accounting, this process is a protected foreground service and should not
be throttled. The freeze happens anyway, which means it's enforced by a vivo-proprietary layer
below the standard `ActivityManagerService` importance system (most likely `com.vivo.abe`,
vivo's "Application Behavior Engine" — its per-app "high background power" screen,
`com.vivo.abe/com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity`, is
signature-permission-gated and can't be opened directly, even via adb).

**Net effect**: on this device, treat "Hey Barfi" as reliable *while the app is open* (foreground
or the screen is on with VaaniMitra as the active app) — not yet as a true always-on background
assistant. The manual "tap mic and speak" path on `ListeningScreen` has no such limitation. If
revisiting this: check for a vivo firmware/OriginOS update (this class of restriction is a known,
frequently-adjusted pain point — see dontkillmyapp.com's vivo entry), or test whether the
restriction is specific to a fresh/cold app rather than one vivo's own heuristics have learned to
trust from repeated real usage over days.
