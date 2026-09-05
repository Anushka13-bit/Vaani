# Personalized On-Device Speech Assistant for Dysarthric Speech
### Architecture & Implementation Plan

**One-line pitch:** *Voiceitt solved this for English speakers who can pay $600/year and wait through lengthy training. We're solving it for Tamil/Indian-language speakers, in minutes not hours, for free, entirely on-device, and working system-wide instead of inside one app.*

---

## 1. Problem Statement

~7.5 million people worldwide have dysarthria (unclear/slurred/atypical speech from cerebral palsy, stroke, Parkinson's, ALS). Mainstream assistants (Siri, Google Assistant) have documented high failure rates on this speech. Google's Project Euphonia targeted this and never shipped self-serve. Voiceitt is a real, shipped competitor — but it's $49.99/mo, closed-source, cloud-hybrid, siloed to its own app, and unclear on Indian-language depth.

**Our gap:** free, on-device, system-wide, Tamil/Indian-language-capable, fast-onboarding personalization.

---

## 2. Full Feature Set

### Tier 1 — Assistant parity (table stakes, must work in demo)
- Voice dictation into any text field (messaging, notes, forms)
- Set alarms / timers / reminders
- Send a message / place a call (by contact name)
- Web search / open an app
- Basic smart-home style actions (stub/simulated is fine for demo)

### Tier 2 — Personalization core (the actual thesis)
- 30–50 sample calibration flow → personal LoRA adapter
- Cluster-adapter warm start (severity/type-matched pretrained adapters as starting point)
- Live before/after WER comparison on a held-out phrase (core demo moment)

### Tier 3 — Differentiators (what makes it hackathon-winning, not a Voiceitt clone)
- **Confidence-gated clarification**: low-confidence words trigger a tap-to-confirm UI instead of silent guessing
- **Personal phrasebook / command shortcuts**: high-frequency phrases mapped directly to actions, bypassing full ASR+NLU
- **Active correction loop**: user corrections become future fine-tuning signal for that user's adapter
- **Caregiver mode**: companion view to review transcripts, add vocabulary, manage phrasebook
- **AAC-lite speak-back**: TTS confirms understood intent before executing irreversible actions (send message, call)
- **Code-switching support**: Tamil–English mixed input handled explicitly, not assumed monolingual
- **System-wide integration**: implemented as an Android `RecognitionService`, so it plugs into the existing keyboard mic button across all apps — not a separate silo app

---

## 3. System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         INPUT LAYER                               │
│  Mic capture (AudioRecord) → on-device VAD (voice activity        │
│  detection, trims silence, detects utterance boundaries)          │
└───────────────────────────────┬─────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PERSONALIZED ASR LAYER (NPU)                   │
│  Base model: Whisper (small/base), frozen, quantized              │
│  + Personal LoRA adapter (few MB, per-user) applied at inference  │
│  Runs via ONNX Runtime Mobile / Qualcomm QNN / whisper.cpp on NPU │
│  Output: transcript + per-token/word confidence scores            │
└───────────────────────────────┬─────────────────────────────────┘
                                 ▼
                  ┌──────────────┴───────────────┐
                  ▼                               ▼
   ┌───────────────────────────┐   ┌───────────────────────────────┐
   │  LOW-CONFIDENCE BRANCH     │   │  PHRASEBOOK MATCH (fast path)  │
   │  → Clarification UI        │   │  Exact/fuzzy match against     │
   │  ("did you mean X or Y?")  │   │  user's registered shortcuts   │
   │  → user taps/confirms      │   │  → skip NLU, direct action     │
   └──────────────┬─────────────┘   └───────────────┬────────────────┘
                  └───────────────┬──────────────────┘
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    INTENT / NLU LAYER                              │
│  Small on-device LLM or lightweight classifier maps transcript     │
│  → structured intent: {action, entities, target_app}               │
│  e.g. "send message to Ravi I'm running late"                     │
│    → {action: SEND_MESSAGE, contact: Ravi, body: "..."}           │
└───────────────────────────────┬─────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ACTION EXECUTION LAYER                            │
│  Android Intents / AccessibilityService actions:                  │
│  - Launch SMS/WhatsApp intent with pre-filled text                 │
│  - AlarmManager for reminders/alarms                                │
│  - Dial intent for calls                                           │
│  - Optional: TTS speak-back for confirmation before send/call      │
└───────────────────────────────┬─────────────────────────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│              CORRECTION / ACTIVE LEARNING LOOP                     │
│  User edits a wrong transcript → correction stored locally         │
│  → periodic incremental LoRA fine-tune using accumulated            │
│    corrections (background, opportunistic, e.g. while charging)    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│         PERSONALIZATION / CALIBRATION SUBSYSTEM (one-time)         │
│  1. User reads 30–50 prompted phrases                              │
│  2. Extract embedding/features from first few samples              │
│  3. Compare to pretrained cluster-adapter centroids                │
│     (clusters built offline from TORGO / UASpeech by severity)     │
│  4. Select nearest cluster adapter as warm start                   │
│  5. Fine-tune LoRA adapter on this user's 30–50 samples,           │
│     initialized from the cluster adapter (fewer steps needed)      │
│  6. Store resulting per-user adapter, encrypted, on-device          │
└─────────────────────────────────────────────────────────────────┘
```

### Data flow summary
`Mic → VAD → Whisper+LoRA (NPU) → confidence check → [clarify | phrasebook | NLU] → intent → Android action → (optional) correction → adapter update`

---

## 4. Component Breakdown

| Component | Purpose | Tech |
|---|---|---|
| VAD | Trim silence, detect utterance start/end | WebRTC VAD or Silero VAD (on-device) |
| Base ASR | Generic speech-to-text backbone | Whisper small/base, quantized (int8) |
| LoRA adapter | Per-user correction layer | `peft` (training), merged/applied at inference |
| Inference runtime | Run ASR on NPU | ONNX Runtime Mobile w/ QNN Execution Provider, or whisper.cpp (GGML) w/ NNAPI delegate |
| Cluster adapters | Warm-start pool by severity/type | Pretrained offline on TORGO + UASpeech, clustered by WER-similarity or dysarthria type label |
| Intent parser | Transcript → structured action | Small on-device LLM (quantized 1–3B, e.g. via llama.cpp) OR rule-based + embedding classifier for reliability under time pressure |
| Phrasebook store | User's custom shortcut phrases | Room DB (SQLite), encrypted at rest |
| Action executor | Perform the actual phone action | Android Intents, AlarmManager, `RecognitionService`/`AccessibilityService` |
| Correction store | Log of user corrections | Room DB, feeds periodic fine-tune job |
| Caregiver view | Secondary UI for review/vocab mgmt | Separate Activity/Compose screen, optional PIN gate |
| Calibration flow | Onboarding data collection + training trigger | Compose UI + background WorkManager job |

---

## 5. Model & Data Plan

**Base model:** Whisper small or base (balance of accuracy vs. mobile inference speed). Full fine-tuning historically outperforms LoRA on this task, but LoRA is chosen deliberately for the storage/scale tradeoff — one adapter per user vs. one full model per user.

**Public datasets for cluster pretraining:** TORGO, UASpeech (English dysarthric speech, standard in this literature).

**Tamil/Indian-language data:** documented as an open, largely unsolved problem in the literature as of 2026 — this is your strongest, most checkable differentiation claim. For the hackathon:
- If no real Tamil dysarthric dataset is accessible, be explicit about this limitation.
- Use a healthy speaker deliberately mimicking dysarthric patterns (slowed, slurred) speaking Tamil/Tanglish for the demo, clearly disclosed as a proxy — matches the "honest caveat" pattern already used for the English calibration flow.
- Stretch goal: collect a small real Tamil calibration set from a volunteer if available locally (Chennai-based team = credible access story to mention to judges even if not executed).

**Calibration set:** 30–50 short prompted phrases per user, **pre-recorded before the demo**, not collected live on stage.

---

## 6. Realistic Hackathon Build Plan

Assume a ~30–36 hour hackathon window. Be honest with the team about what runs live on-device vs. what is prepared in advance.

### Phase 0 — Before the hackathon (prep)
- Download Whisper small/base, set up `peft` LoRA training pipeline on a laptop/cloud GPU.
- Download TORGO/UASpeech, pretrain 3–5 cluster adapters offline (by rough severity bucket).
- Pre-record calibration phrase sets (English proxy + Tamil/Tanglish proxy) with a volunteer.
- Scaffold Android app (Kotlin, Jetpack Compose), request mic + `RecognitionService` permissions.

### Phase 1 (Hours 0–6) — Baseline pipeline
- Get quantized Whisper running on-device via ONNX Runtime Mobile or whisper.cpp.
- Confirm live mic → transcript works end-to-end (generic model, no personalization yet).
- This is your fallback demo if nothing else works — get it rock-solid first.

### Phase 2 (Hours 6–14) — Personalization
- Load a pre-trained cluster adapter and per-user fine-tuned adapter (trained offline in Phase 0/on a laptop during the hackathon) onto the device.
- Wire up adapter-swapping at inference (base + adapter A vs. base + adapter B).
- Build the before/after WER comparison screen — this is your core demo moment, get it visually clear.

### Phase 3 (Hours 14–22) — Intent + actions
- Implement 4–5 core intents: dictate-to-field, send message, set reminder, call contact, web search.
- Wire transcript → intent parser → Android Intent execution.
- Add confidence-gated clarification UI for low-confidence words.

### Phase 4 (Hours 22–28) — Differentiators
- Phrasebook: let user register 3–5 shortcut phrases live in the demo.
- Correction loop: show a wrong transcript being corrected, log it (full retraining can be described, not necessarily executed live).
- Optional: TTS speak-back confirmation before sending/calling.

### Phase 5 (Hours 28–32) — System-wide integration
- Register app as a `RecognitionService` so it's selectable as the system voice-input method, demonstrating it working inside a third-party app's mic button (e.g., a stock Notes or Messages app).

### Phase 6 (Hours 32–36) — Polish + rehearse
- Finalize before/after WER demo script.
- Rehearse the "honest caveats" framing (proxy speaker, pre-trained adapters, calibration pre-recorded).
- Prepare the competitive-landscape slide (Voiceitt, Project Relate, Euphonia) — leading with this preempts the "doesn't this exist" objection.

---

## 7. Demo Script (5–7 min)

1. **Hook (30s):** State the problem + name Voiceitt/Project Relate/Euphonia upfront. "This category exists — here's exactly where the gap is."
2. **Live generic ASR fail (30s):** Speak a proxy-dysarthric phrase into stock Google/Siri-style dictation → show garbled output.
3. **Switch to personalized adapter (1 min):** Same phrase, same device, personal adapter loaded → correct transcription. This is the money shot.
4. **System-wide proof (1 min):** Open a third-party app (Notes/Messages), tap its mic button, show your `RecognitionService` handling it — proves it's not a silo.
5. **Action execution (1 min):** Voice command → "send message to Amma, running late" → confirm via TTS speak-back → message sent.
6. **Differentiator montage (1–2 min):** Confidence-gated clarification tap, phrasebook shortcut firing instantly, caregiver view.
7. **Close (30s):** Cluster-adapter warm-start explanation + Tamil/regional angle + free/on-device/open-source framing vs. Voiceitt's $600/year.

---

## 8. Honest Caveats to State Proactively

- 30–50 calibration samples were pre-recorded before the demo, not collected live.
- If no genuine dysarthric volunteer is available, a healthy speaker mimicking dysarthric speech patterns is used as an explicit, disclosed proxy.
- Tamil dysarthric training data is scarce/unsolved in the literature — the Tamil demo may rely on the same proxy-speaker approach, not a validated clinical dataset.
- LoRA (vs. full fine-tuning) is a deliberate storage/scalability tradeoff, not a claim that it's more accurate — literature shows full fine-tuning can outperform LoRA on this specific task.
- This closes a personalization gap for individual users, not general-purpose ASR robustness — frame it that way, not as "we beat Whisper at ASR."

---

## 9. Competitive Positioning (say this before judges ask)

| | Voiceitt | Google Project Relate/Euphonia | This project |
|---|---|---|---|
| Cost | $49.99/mo | Free (research/limited) | Free |
| Deployment | Cloud-hybrid app | Never fully shipped self-serve | Fully on-device |
| Scope | Its own app only | Its own app only | System-wide via `RecognitionService` |
| Language depth | Unclear outside English/major EU languages | English-centric | Explicit Tamil/Indian-language focus |
| Onboarding | Reported as time-consuming | N/A | Cluster-adapter warm start, minutes not hours |
| Openness | Closed-source | Closed/research | Open pipeline (Whisper + `peft`) |
