# Literature Review: Personalized On-Device ASR for Dysarthric Speech

## 1. What the Literature Establishes (and Who's Done It)

### A. LoRA/PEFT Personalization for Dysarthric ASR — Very Active, Now Well-Characterized

- **Muller, Tóth & Roberts (arXiv:2609.02735, Sept 2026)** — *"Choosing a PEFT Variant for Per-Patient Dysarthric ASR."*
  - Directly compares 7 LoRA-family methods (LoRA, QLoRA, DoRA, AdaLoRA, LoHA, VeRA, VB-LoRA) on Whisper-large-v3 and Qwen3-ASR for one severe post-stroke speaker.
  - **Key findings:**
    - Plain LoRA is the right choice.
    - No significant DoRA gain.
    - QLoRA actually performs worse.
    - LoRA reaches within **0.66 percentage points** of full fine-tuning at approximately **3.7% of the storage**.
    - A 6-point enrollment grid shows that approximately **5 minutes of audio captures ~46% of the achievable WER reduction**.
  - This validates your mechanism almost exactly — cite it as evidence, but note it also means **"LoRA on Whisper for dysarthria" is no longer novel by itself.**

- **Jiang et al., Interspeech 2024 (arXiv:2406.09873)** — *Perceiver-Prompt*
  - Uses LoRA fine-tuned Whisper + speaker prompts.
  - Reports a **13% relative CER reduction** on Chinese dysarthric speech.

- **Moure et al. (2026)**
  - LoRA adaptation with clinical prompting achieves **0.066 WER**, a **52% relative reduction**, on a Down-syndrome/inclusive dataset.

- **Agarwal et al. (arXiv:2509.15516, Google authors)** — *"The Universal Personalizer: Few-Shot Dysarthric ASR via Meta-Learning"*
  - Demonstrates zero/few-shot in-context personalization.
  - Reports **13.9% WER** on Euphonia data.
  - This is the closest thing to your **"cluster warm start"** idea and is scientifically stronger than clustering.
  - Your warm-start claim should therefore be framed as a **"simpler, more deployable on-device alternative,"** rather than as new science.

### B. Personalization as the Accepted Frontier

- **Zhang, de Groot, Scharenborg et al. (arXiv:2606.30237, 2026)** — Dutch severe dysarthria.
  - Humans and off-the-shelf SOTA ASR both exceed **70% WER**.
  - Personalized fine-tuned models beat human listeners.
  - Confirms the framing that **personalization is the gap and generic ASR is inadequate**.

- **Singh et al., ICASSP 2025**
  - Investigates cross-etiology transfer.
  - Reports **CER 6.99% in-domain** and **25% cross-etiology** on TORGO.
  - Supports the idea of **severity/etiology-grouped pretraining**.

- **Google patent activity on personalized dysarthric ASR**
  - Corroborates commercial interest.
  - Fine to mention as evidence of industry activity.

### C. Tamil Dysarthric ASR — Confirmed Wide Open

- arXiv has only **3 papers combining "dysarthria" with Tamil/Indian** (Yeo et al. 2022, 2024, 2026).
- All three are **severity assessment/classification**, not recognition.
- None ship a recognizer.
- This is the strongest empirical confirmation of the gap claim:
  - **No published Tamil dysarthric ASR system**
  - **No dataset of scale**
  - **No product**

### D. Voiceitt — Competitor, Verified

Their site confirms:

- English only: **"Voiceitt is now available in English around the world."**
- A web app, rather than system-wide Android integration.
- Closed-source.
- SLP-assisted onboarding: **"our dedicated team of speech-language pathologists will support you."**
- Integrations via Webex, Teams, Zoom, Alexa, and ChatGPT.
- The **$49.99/month pricing claim could not be independently verified** from the fetch, so soften it to **"enterprise-priced, per-seat"** or verify the current price page before putting a number on a slide.
- No Indian-language roadmap was found.

### E. Project Relate

- Google's Project Relate page has moved; the previously checked URL returned a 404.
- Relate exists as an **English-only Android app in select markets**.
- It personalizes but is:
  - not open-source,
  - not system-wide as an Android `RecognitionService`,
  - not available for Indian languages.
- Your **system-service integration + open pipeline** therefore remains a differentiator.

---

## 2. Honest Scorecard of the Four Claimed Differentiators

| # | Claim | Verdict |
|---|---|---|
| 1 | Tamil/Indian-language dysarthric ASR | ✅ **Strongest, survives scrutiny.** Literature confirms zero recognition systems for Tamil. |
| 2 | Cluster-adapter warm start | ⚠️ **Must be reframed.** Meta-learning (Agarwal et al.) already beats the concept scientifically; pitch it as an engineering/deployment choice, not novelty. |
| 3 | Fully on-device, open, free | ✅ **Holds, but is a positioning advantage, not a research one.** Relate is free too; your edge over Relate is open + system-wide + multilingual. |
| 4 | System-wide `RecognitionService` | ✅ **Holds.** Voiceitt is explicitly a standalone web app; Relate is in-app. A `RecognitionService` plugin is genuinely unshipped by anyone. |

---

## 3. The USP (Refined)

> **The first dysarthric speech recognizer for Tamil and Indian languages — open-source, running entirely on a phone's NPU, personalized in minutes with a lightweight per-user LoRA adapter, and plugged into the entire Android keyboard/voice-input system rather than one app.**

### Four Load-Bearing Pillars

Listed in order of defensibility:

1. **Language gap (novelty)**
   - No Tamil dysarthric ASR exists in the literature or market — only severity assessment.

2. **System-wide integration (product gap)**
   - Nobody — not Voiceitt, not Relate — exposes personalization through Android's `RecognitionService`.

3. **Open + on-device + free (access gap)**
   - Contrasts with Voiceitt's closed, enterprise-priced web app.

4. **Minutes-not-hours onboarding (UX claim)**
   - Backed by Muller's finding that approximately **5 minutes of enrollment captures half of the achievable gains**.
   - Cite it, and frame your **cluster warm start** as how you get the other half cheaply.

---

## 4. Refinement to the Pitch

Avoid:

> **"We invented LoRA personalization."**

The September 2026 PEFT comparison paper means judges may already know that LoRA personalization for dysarthric ASR is established.

Instead, use:

> **"The personalization mechanism is proven in the literature; what's missing is the language (Tamil), the platform (system-wide Android), and the access model (open, on-device, free). We're filling all three."**

This framing is more defensible because it does not claim novelty where the literature already establishes prior work.

---

## 5. Caveats to Keep on the Slide

Be explicit about the following caveats:

- **Demo-speech proxy issue**
- **Pre-recorded calibration** (per the project context)
- **Verify the Voiceitt price figure before quoting it numerically**

These caveats help keep the presentation technically honest and prevent the USP from overstating what has been experimentally demonstrated.

---

## 6. Bottom Line

The strongest defensible story is **not** that the project invented personalized dysarthric ASR.

The literature already establishes LoRA/PEFT personalization as an active and increasingly well-characterized approach. The stronger argument is that the project combines three gaps:

1. **Language:** Tamil and Indian-language dysarthric ASR.
2. **Platform:** system-wide Android `RecognitionService` integration.
3. **Access:** open-source, on-device, and free.

The cluster-adapter warm start should be presented as an **engineering/deployment strategy** for making personalization practical on-device, rather than as the core scientific novelty.

The project can therefore position itself as taking a proven personalization mechanism and delivering it in a combination of **language coverage, system-level integration, and accessibility** that existing systems do not provide.
