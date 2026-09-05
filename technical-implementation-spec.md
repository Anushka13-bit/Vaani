# Personalized Dysarthric Speech Assistant
## End-to-End Technical Implementation Spec (Build-Agent Handoff)

This document is written to be handed directly to a build agent. It defines: full system architecture, every service boundary, all API endpoints with request/response schemas, all data models (on-device and backend), sequence flows for every major user journey, and the repo/module layout to generate.

---

## 1. System Overview

The system has **three deployable units**:

1. **Mobile App** (React Native, JS/TS) + a **required native Android module layer** (Kotlin) — RN owns UI: calibration flow, settings, phrasebook management, caregiver mode, transcript display, networking to the backend. The native Kotlin layer owns everything that *must* be an Android system service or run tight against the NPU: `RecognitionService`, `AccessibilityService`, and the Whisper+LoRA inference engine. These two layers communicate via React Native's Native Modules (or TurboModules) and Native Event Emitters.
2. **Training Backend** (Python/FastAPI) — off-device. Owns LoRA adapter training (cluster adapters + per-user adapters), adapter storage/versioning, and optional correction-driven retraining. This does NOT run during live usage — only during onboarding/calibration and periodic retraining.
3. **Caregiver Web Dashboard** (optional, React) — thin client against the Training Backend's caregiver endpoints. Lets a caregiver review low-confidence transcripts and manage the phrasebook remotely.

**Critical constraint #1:** on-device training of Whisper+LoRA is not currently practical on a phone in real time. All *training* happens on the backend; the phone only ever runs *inference* using downloaded adapter weights (a few MB file). This must not be violated when the agent builds this — do not attempt on-device fine-tuning loops.

**Critical constraint #2 (RN-specific):** `android.speech.RecognitionService` and `android.accessibilityservice.AccessibilityService` are Android system service classes with no React Native or JavaScript equivalent — they cannot be implemented in JS and there is no community RN package that provides them. They must be written as native Kotlin classes, declared in `AndroidManifest.xml`, and exposed to the RN JS layer via a bridge. Do not attempt to satisfy these requirements with a pure-JS/RN library — none exists that performs true system-level `RecognitionService`/`AccessibilityService` registration.

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         MOBILE APP (on-device)                              │
│                                                                               │
│  ┌─────────────────────────────┐        ┌────────────────────────────────┐ │
│  │  REACT NATIVE LAYER (JS/TS)  │◄──────►│   NATIVE ANDROID LAYER (Kotlin) │ │
│  │                               │ Native │                                  │ │
│  │  Calibration UI               │ Module │  PersonalizedRecognitionService │ │
│  │  Settings / Phrasebook UI     │ Bridge │  AccessibilityActionService     │ │
│  │  Transcript / caregiver views │ (+     │  WhisperInferenceEngine         │ │
│  │  HTTP client → backend        │ Event  │   (Whisper + LoRA, NPU)         │ │
│  │  Local storage (AsyncStorage/ │Emitter)│  AdapterManager                 │ │
│  │  SQLite via RN bridge)        │        │  ConfidenceScorer                │ │
│  └─────────────────────────────┘        └────────────────────────────────┘ │
│                                                        │                      │
│                                          TextToSpeech.speak() confirmation   │
│                                          (Android system TTS, via RN plugin  │
│                                           or native module)                  │
│                                                        │                      │
│                        Correction Store → sync job (opt-in) ─────────────────┼──┐
└───────────────────────────────────────────────────────────────────────────┘  │
                                                                                  │ HTTPS (opt-in only)
┌────────────────────────────────────────────────────────────────────┐        │
│                  TRAINING BACKEND (FastAPI, off-device)               │◄───────┘
│                                                                        │
│  Calibration API → Training Job Queue → LoRA Trainer (GPU)            │
│  Adapter Registry (versioned, per-user + per-cluster + per-language)  │
│  Correction Ingestion → Periodic Retrain Trigger                       │
│  Caregiver API (auth, transcript review, phrasebook sync)              │
└────────────────────────────────────────────────────────────────────┘
                                                                                  │
┌────────────────────────────────────────────────────────────────────┐        │
│               CAREGIVER WEB DASHBOARD (optional, React)               │◄───────┘
└────────────────────────────────────────────────────────────────────┘
```

---

## 2. Mobile App Architecture (React Native + Native Android Modules)

The app is split into two codebases that must both exist: a React Native JS/TS layer for everything UI/state/networking, and a native Android Kotlin layer for everything that requires system-service registration or tight NPU access. **Neither layer is optional** — RN cannot replace the native layer for `RecognitionService`/`AccessibilityService`, and building the whole UI natively would be slower and defeats the point of choosing RN.

### 2.1 Module layout

```
mobile-app/
├── src/                                     # React Native (JS/TS) — UI, state, networking
│   ├── screens/
│   │   ├── CalibrationScreen.tsx             # onboarding flow, records + uploads samples
│   │   ├── SettingsScreen.tsx
│   │   ├── PhrasebookScreen.tsx              # CRUD UI for shortcut phrases
│   │   ├── CaregiverModeScreen.tsx           # PIN-gated secondary view
│   │   └── TranscriptHistoryScreen.tsx
│   ├── native/
│   │   ├── SpeechBridge.ts                   # typed wrapper around the native module, see §2.2
│   │   ├── AccessibilityBridge.ts            # typed wrapper around AccessibilityActionModule
│   │   └── types.ts                          # shared TS types mirroring native data classes
│   ├── api/
│   │   ├── trainingBackendClient.ts          # fetch/axios client, see §5
│   │   └── dto.ts                            # request/response types
│   ├── state/                                # Redux/Zustand/Context — adapter status, phrasebook cache
│   ├── storage/
│   │   └── localDb.ts                        # SQLite (react-native-sqlite-storage or WatermelonDB)
│   └── App.tsx
├── android/                                  # native Android project (required)
│   └── app/src/main/java/.../
│       ├── audio/
│       │   ├── AudioCaptureManager.kt        # AudioRecord wrapper, 16kHz mono PCM
│       │   └── VoiceActivityDetector.kt      # Silero/WebRTC VAD wrapper
│       ├── stt/
│       │   ├── WhisperInferenceEngine.kt     # ONNX Runtime Mobile / whisper.cpp bridge
│       │   ├── AdapterManager.kt             # loads/swaps LoRA adapter files
│       │   └── ConfidenceScorer.kt           # per-token/word confidence extraction
│       ├── recognition/
│       │   └── PersonalizedRecognitionService.kt   # extends android.speech.RecognitionService
│       ├── nlu/
│       │   ├── IntentParser.kt               # transcript -> Intent data class
│       │   └── PhrasebookMatcher.kt          # fuzzy match against user shortcuts
│       ├── actions/
│       │   ├── ActionExecutor.kt             # dispatches to below
│       │   ├── AndroidIntentActions.kt       # SMS, dial, alarm, search, app-open
│       │   └── AccessibilityActionService.kt # extends android.accessibilityservice.AccessibilityService
│       ├── tts/
│       │   └── SpeakBackManager.kt           # wraps android.speech.tts.TextToSpeech
│       ├── bridge/
│       │   ├── SpeechModule.kt               # @ReactModule — exposes STT/adapter APIs to RN
│       │   ├── SpeechModulePackage.kt        # ReactPackage registration
│       │   ├── AccessibilityModule.kt        # @ReactModule — exposes action execution to RN
│       │   └── RecognitionEventEmitter.kt    # streams transcript/confidence events to RN
│       └── security/
│           └── KeystoreCrypto.kt             # Android Keystore-backed encryption for adapters/DB
├── ios/                                      # present for RN project structure; native STT/action
│                                              # parity is Android-only for this project (RecognitionService
│                                              # and AccessibilityService are Android concepts) — treat iOS
│                                              # as out of scope unless explicitly required
└── package.json
```

### 2.2 React Native ↔ Native bridge contract

This is the piece that's new versus a pure-Kotlin app. Two mechanisms are used:

- **Native Modules** (`SpeechModule.kt`, `AccessibilityModule.kt`) — synchronous/promise-based calls **from RN into native** (e.g. "load this adapter", "execute this parsed intent").
- **Native Event Emitters** (`RecognitionEventEmitter.kt`) — asynchronous, streaming events **from native into RN** (e.g. live transcript segments and confidence scores as they arrive, so the RN UI can render the clarification prompt in real time).

```kotlin
// android/.../bridge/SpeechModule.kt
class SpeechModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName() = "SpeechModule"

    @ReactMethod
    fun loadUserAdapter(userId: String, promise: Promise) {
        // AdapterManager.loadUserAdapter(userId) -> resolve(adapterHandleAsWritableMap) / reject(error)
    }

    @ReactMethod
    fun loadLanguageAdapter(languageCode: String, promise: Promise) { /* ... */ }

    @ReactMethod
    fun transcribeFile(audioFilePath: String, promise: Promise) {
        // Used for calibration sample review / before-after demo comparisons.
        // Live streaming dictation goes through PersonalizedRecognitionService directly,
        // NOT through this module — RecognitionService is bound by the OS, not called from RN.
    }

    @ReactMethod
    fun getCurrentAdapterInfo(promise: Promise) { /* ... */ }
}

// android/.../bridge/AccessibilityModule.kt
class AccessibilityModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName() = "AccessibilityModule"

    @ReactMethod
    fun isAccessibilityServiceEnabled(promise: Promise) { /* checks system settings */ }

    @ReactMethod
    fun openAccessibilitySettings() { /* deep-links to system settings for user to enable it */ }

    @ReactMethod
    fun executeParsedIntent(intentJson: String, promise: Promise) {
        // Deserializes ParsedIntent, routes to ActionExecutor.execute(intent)
    }
}

// android/.../bridge/RecognitionEventEmitter.kt
class RecognitionEventEmitter(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    override fun getName() = "RecognitionEventEmitter"

    fun emitTranscriptSegment(segment: TranscriptSegment) {
        val params = Arguments.createMap().apply {
            putString("text", segment.text)
            putDouble("confidence", segment.confidence.toDouble())
            putDouble("startMs", segment.startMs.toDouble())
        }
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onTranscriptSegment", params)
    }
}
```

```typescript
// src/native/SpeechBridge.ts — typed RN-side wrapper
import { NativeModules, NativeEventEmitter } from 'react-native';

const { SpeechModule } = NativeModules;
const recognitionEvents = new NativeEventEmitter(NativeModules.RecognitionEventEmitter);

export interface AdapterHandle {
  adapterId: string;
  version: number;
  type: 'USER' | 'LANGUAGE' | 'CLUSTER';
}

export interface TranscriptSegment {
  text: string;
  confidence: number;
  startMs: number;
}

export const SpeechBridge = {
  loadUserAdapter: (userId: string): Promise<AdapterHandle> =>
    SpeechModule.loadUserAdapter(userId),

  loadLanguageAdapter: (languageCode: string): Promise<AdapterHandle> =>
    SpeechModule.loadLanguageAdapter(languageCode),

  transcribeFile: (audioFilePath: string): Promise<{ text: string; confidence: number }> =>
    SpeechModule.transcribeFile(audioFilePath),

  onTranscriptSegment: (callback: (segment: TranscriptSegment) => void) =>
    recognitionEvents.addListener('onTranscriptSegment', callback),
};
```

### 2.3 Key native interfaces (unchanged from the Kotlin-only design — still required regardless of UI framework)

```kotlin
interface SttEngine {
    /**
     * Runs inference using base Whisper + currently loaded LoRA adapter(s).
     * Returns transcript segments with per-segment confidence.
     */
    suspend fun transcribe(pcmAudio: ShortArray, sampleRate: Int = 16000): TranscriptionResult
}

data class TranscriptionResult(
    val text: String,
    val languageDetected: String,          // e.g. "ta", "en", or "ta-en" for code-switch
    val segments: List<TranscriptSegment>
)

data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float                  // 0.0 - 1.0
)

interface AdapterManager {
    suspend fun loadUserAdapter(userId: String): AdapterHandle
    suspend fun loadLanguageAdapter(languageCode: String): AdapterHandle
    fun currentStackedAdapters(): List<AdapterHandle>   // language adapter + user adapter, stacked
}

data class AdapterHandle(
    val adapterId: String,
    val version: Int,
    val type: AdapterType,                 // USER | LANGUAGE | CLUSTER
    val filePath: String,                  // local encrypted file path
    val checksum: String
)

enum class AdapterType { USER, LANGUAGE, CLUSTER }

interface IntentParser {
    fun parse(transcript: String): ParsedIntent
}

data class ParsedIntent(
    val action: ActionType,
    val entities: Map<String, String>,     // e.g. {"contact": "Ravi", "body": "running late"}
    val confidence: Float,
    val requiresConfirmation: Boolean      // true for irreversible actions (send/call)
)

enum class ActionType {
    DICTATE_TEXT, SEND_MESSAGE, SET_REMINDER, SET_ALARM,
    PLACE_CALL, WEB_SEARCH, OPEN_APP, SMART_HOME_ACTION, UNKNOWN
}

interface ActionExecutor {
    suspend fun execute(intent: ParsedIntent): ActionResult
}

data class ActionResult(
    val success: Boolean,
    val message: String,
    val requiresAccessibilityFallback: Boolean = false
)
```

---

## 3. Sequence Flows

### 3.1 Onboarding / Calibration

```
User opens app → RN CalibrationScreen.tsx
   → trainingBackendClient: GET /v1/calibration/prompts?language=ta   (backend returns prompt list)
   → User records each prompt (30-50 samples) — RN mic capture (e.g. react-native-audio-recorder-player)
   → trainingBackendClient: POST /v1/calibration/sessions                       (create session)
   → trainingBackendClient: POST /v1/calibration/sessions/{id}/samples  (x N)    (upload each sample)
   → trainingBackendClient: POST /v1/calibration/sessions/{id}/train              (trigger training)
   → poll GET /v1/calibration/sessions/{id}/status  until COMPLETE
   → GET /v1/adapters/{adapter_id}/download                (fetch trained adapter file, save to native storage path)
   → RN calls SpeechBridge.loadUserAdapter(userId)
        → native SpeechModule.loadUserAdapter() → AdapterManager stores adapter locally (encrypted),
          marks as active USER adapter
        → Promise resolves back to RN with AdapterHandle for UI confirmation
```

### 3.2 Live dictation (system-wide, via RecognitionService)

```
Any app's mic button tapped (system-wide — this path does NOT go through RN)
   → Android SpeechRecognizer routes to PersonalizedRecognitionService (user's chosen default)
   → AudioCaptureManager streams PCM → VoiceActivityDetector trims silence
   → SttEngine.transcribe() [Whisper + stacked LoRA adapters, on NPU]
   → ConfidenceScorer flags low-confidence segments
   → IF low confidence: return partial result AND RecognitionEventEmitter.emitTranscriptSegment()
        → if the RN app is foregrounded, this drives the clarification UI overlay in RN;
          if the calling app is a third-party app (RN app backgrounded), the clarification
          must be handled via a native overlay (Android SYSTEM_ALERT_WINDOW) since RN JS
          cannot render UI on top of another app — flag this as a build risk, see §9
   → ELSE: return final transcript to calling app via RecognitionService.Callback (native-only,
     RN is not involved in this path at all)
```

### 3.3 Voice command → action, with confirmation

```
Transcript ready (native side)
   → PhrasebookMatcher.match(transcript)   [phrasebook cache synced down from RN's local SQLite store
     into native at app start, so matching can happen natively without a bridge round-trip per word]
        → HIT: skip NLU, go straight to ActionExecutor with stored action template
        → MISS: continue to IntentParser.parse(transcript)
   → ParsedIntent produced (native)
   → IF requiresConfirmation: SpeakBackManager.speak("Sending message to Ravi: running late")
        → RecognitionEventEmitter notifies RN of pending confirmation (for in-app UI state)
        → wait for user tap-to-confirm (RN UI, via AccessibilityModule.executeParsedIntent
          call once confirmed) or voice "yes" (handled natively, no RN round-trip needed
          for speed)
   → ActionExecutor.execute(intent)
        → simple case: AndroidIntentActions fires standard Intent (SMS/dial/alarm)
        → complex case: AccessibilityActionService taps/fills fields in target app
   → ActionResult returned; if RN app is foregrounded, emitted as an event for UI update
```

### 3.4 Correction → retraining loop (opt-in only)

```
User edits a wrong transcript in any RN text field or a system dictation target
   → RN CorrectionRepository (src/state or local SQLite) stores
     {original_transcript, corrected_transcript, audio_ref, timestamp} locally
   → IF user has opted into cloud-assisted retraining (RN Settings toggle):
        RN background task (e.g. react-native-background-fetch, or a native WorkManager
        job triggered via a native module for reliability — WorkManager is more dependable
        than RN's JS-side background tasks for scheduling around charging/WiFi state)
        → trainingBackendClient: POST /v1/corrections  (batch upload, encrypted transport)
   → Backend accumulates corrections per user
   → When threshold reached (e.g. 20 new corrections): backend auto-triggers
     a retrain job using the existing user adapter as warm start
   → New adapter version pushed; RN polls GET /v1/users/{id}/adapter, and on a new version,
     calls SpeechBridge.loadUserAdapter(userId) again to refresh the native-side adapter
```

---

## 4. Data Models

### 4.1 On-device storage — split across RN and native

Two local stores exist and must stay in sync:

- **RN-side local DB** (SQLite via `react-native-sqlite-storage` or WatermelonDB, or simply `AsyncStorage` for the phrasebook/settings if volume stays small): owns phrasebook entries, correction records, transcript history for display, and user settings/consent flags — anything the RN UI reads/writes directly.
- **Native-side Room DB**: owns `adapters` (binary file references, checksums) and a lightweight mirrored copy of `phrasebook_entries` for fast native-side matching in `PhrasebookMatcher` without a bridge call per utterance. On phrasebook edits in RN, call a native module method to push the updated entry down into the native mirror table.

The Kotlin entity definitions below apply to the **native Room DB**; a corresponding TypeScript interface set (not shown, straightforward 1:1 mapping) should back the **RN-side SQLite tables**.

```kotlin
@Entity(tableName = "phrasebook_entries")
data class PhrasebookEntry(
    @PrimaryKey val id: String,
    val userId: String,
    val triggerPhrase: String,          // what the user says
    val actionType: String,             // maps to ActionType enum
    val actionPayloadJson: String,      // serialized entities for the action
    val createdAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int = 0
)

@Entity(tableName = "corrections")
data class CorrectionRecord(
    @PrimaryKey val id: String,
    val userId: String,
    val originalTranscript: String,
    val correctedTranscript: String,
    val audioFilePath: String?,         // nullable — audio may be deleted after local use
    val confidenceAtTime: Float,
    val createdAt: Long,
    val syncedToBackend: Boolean = false
)

@Entity(tableName = "adapters")
data class AdapterRecord(
    @PrimaryKey val adapterId: String,
    val userId: String?,                // null for language/cluster adapters
    val type: String,                   // USER | LANGUAGE | CLUSTER
    val languageCode: String,
    val severityCluster: String?,       // e.g. "moderate", null if not applicable
    val version: Int,
    val localFilePath: String,
    val checksum: String,
    val downloadedAt: Long,
    val isActive: Boolean
)

@Entity(tableName = "transcript_log")
data class TranscriptLogEntry(
    @PrimaryKey val id: String,
    val userId: String,
    val transcript: String,
    val confidence: Float,
    val sourceApp: String,              // package name of the app that requested STT
    val timestamp: Long,
    val flaggedForCaregiverReview: Boolean = false
)
```

### 4.2 Backend data models (Pydantic / DB schema)

```python
class User(BaseModel):
    user_id: str
    device_id: str
    preferred_language: str            # e.g. "ta", "en", "ta-en"
    dysarthria_severity_hint: str | None   # user-reported, optional, used for cluster selection
    created_at: datetime

class CalibrationSession(BaseModel):
    session_id: str
    user_id: str
    prompt_set_id: str
    status: Literal["CREATED", "COLLECTING", "TRAINING", "COMPLETE", "FAILED"]
    samples_received: int
    samples_required: int
    created_at: datetime
    updated_at: datetime

class CalibrationSample(BaseModel):
    sample_id: str
    session_id: str
    prompt_text: str
    audio_storage_path: str            # object storage path, encrypted at rest
    duration_ms: int
    uploaded_at: datetime

class AdapterRecordBackend(BaseModel):
    adapter_id: str
    type: Literal["USER", "LANGUAGE", "CLUSTER"]
    user_id: str | None
    language_code: str
    severity_cluster: str | None       # e.g. "mild" | "moderate" | "severe"
    base_model: str                    # e.g. "openai/whisper-small"
    parent_adapter_id: str | None      # warm-start lineage
    version: int
    storage_path: str
    checksum: str
    training_job_id: str | None
    created_at: datetime

class TrainingJob(BaseModel):
    job_id: str
    session_id: str | None             # set for calibration-triggered jobs
    user_id: str | None
    trigger: Literal["CALIBRATION", "CORRECTION_THRESHOLD", "MANUAL"]
    status: Literal["QUEUED", "RUNNING", "SUCCEEDED", "FAILED"]
    resulting_adapter_id: str | None
    started_at: datetime | None
    completed_at: datetime | None
    error_message: str | None

class Correction(BaseModel):
    correction_id: str
    user_id: str
    original_transcript: str
    corrected_transcript: str
    audio_storage_path: str | None
    confidence_at_time: float
    received_at: datetime

class CaregiverLink(BaseModel):
    caregiver_id: str
    user_id: str
    relationship: str | None
    permissions: list[Literal["VIEW_TRANSCRIPTS", "EDIT_PHRASEBOOK"]]
    linked_at: datetime
```

---

## 5. Backend API Specification (FastAPI, base path `/v1`)

Authentication: bearer token per device (issued at first app launch via `/v1/auth/device`). Caregiver endpoints additionally require a caregiver session token.

### 5.1 Auth

**`POST /v1/auth/device`**
Registers a device/user pair, issues an access token.
```json
// Request
{ "device_id": "string", "preferred_language": "ta" }

// Response 201
{ "user_id": "string", "access_token": "string", "expires_at": "2026-10-01T00:00:00Z" }
```

### 5.2 Calibration

**`GET /v1/calibration/prompts?language=ta&count=40`**
Returns the phrase list the app will prompt the user to read.
```json
// Response 200
{
  "prompt_set_id": "string",
  "prompts": [
    { "prompt_id": "p1", "text": "..." , "language": "ta" }
  ]
}
```

**`POST /v1/calibration/sessions`**
```json
// Request
{ "user_id": "string", "prompt_set_id": "string" }

// Response 201
{ "session_id": "string", "status": "CREATED", "samples_required": 40 }
```

**`POST /v1/calibration/sessions/{session_id}/samples`**
Multipart upload: audio file + `prompt_id` field.
```json
// Response 201
{ "sample_id": "string", "samples_received": 12, "samples_required": 40 }
```

**`POST /v1/calibration/sessions/{session_id}/train`**
Triggers async training job. Backend selects warm-start cluster/language adapter automatically based on `dysarthria_severity_hint` + `preferred_language`.
```json
// Response 202
{ "job_id": "string", "status": "QUEUED" }
```

**`GET /v1/calibration/sessions/{session_id}/status`**
```json
// Response 200
{
  "status": "TRAINING",
  "job_id": "string",
  "progress_pct": 65,
  "resulting_adapter_id": null
}
// or once complete:
{
  "status": "COMPLETE",
  "job_id": "string",
  "progress_pct": 100,
  "resulting_adapter_id": "adapter_abc123"
}
```

### 5.3 Adapters

**`GET /v1/adapters/{adapter_id}`**
```json
// Response 200
{
  "adapter_id": "adapter_abc123",
  "type": "USER",
  "language_code": "ta",
  "severity_cluster": "moderate",
  "version": 3,
  "checksum": "sha256:...",
  "download_url": "https://.../adapter_abc123.bin"
}
```

**`GET /v1/adapters/{adapter_id}/download`**
Binary response (adapter weights file, few MB), `Content-Type: application/octet-stream`.

**`GET /v1/adapters/clusters?language=ta&severity=moderate`**
Returns the best-matching pretrained cluster adapter for warm-starting a new user.
```json
// Response 200
{ "adapter_id": "cluster_ta_moderate_v2", "version": 2, "download_url": "https://..." }
```

**`GET /v1/users/{user_id}/adapter`**
Returns the currently active adapter for polling/update checks.
```json
// Response 200
{ "adapter_id": "adapter_abc123", "version": 3, "updated_at": "2026-09-01T10:00:00Z" }
```

### 5.4 Corrections

**`POST /v1/corrections`**
Batch upload of local corrections (opt-in only; app must have user consent flag set before calling this).
```json
// Request
{
  "user_id": "string",
  "corrections": [
    {
      "original_transcript": "sen bils to ravi",
      "corrected_transcript": "send bills to Ravi",
      "confidence_at_time": 0.42,
      "audio_included": true
    }
  ]
}
// Response 202
{ "accepted": 5, "retrain_triggered": false }
```

### 5.5 Caregiver

**`POST /v1/caregiver/link`**
```json
// Request
{ "user_id": "string", "caregiver_email": "string", "permissions": ["VIEW_TRANSCRIPTS"] }
// Response 201
{ "caregiver_id": "string", "status": "PENDING_USER_APPROVAL" }
```

**`GET /v1/caregiver/{user_id}/transcripts?confidence_lt=0.6&limit=50`**
```json
// Response 200
{
  "transcripts": [
    { "id": "string", "transcript": "...", "confidence": 0.41, "timestamp": "..." }
  ]
}
```

**`PUT /v1/caregiver/{user_id}/phrasebook/{entry_id}`**
```json
// Request
{ "trigger_phrase": "my meds", "action_type": "SET_REMINDER", "action_payload": {"label": "Medication"} }
// Response 200
{ "entry_id": "string", "updated": true }
```

### 5.6 Error format (all endpoints)

```json
{
  "error": {
    "code": "SESSION_NOT_FOUND",
    "message": "human readable string",
    "request_id": "string"
  }
}
```
Standard HTTP status codes: `400` validation, `401` auth, `404` not found, `409` conflict (e.g. training already in progress), `422` unprocessable (e.g. malformed audio), `500` server error.

---

## 6. Training Backend — Internal Pipeline

```
POST /train endpoint received
   → enqueue TrainingJob (status=QUEUED) on job queue (Celery/RQ + Redis, or simple DB-polled queue for hackathon scale)
   → worker picks up job:
        1. resolve warm-start adapter:
             if session has severity_hint + language → GET cluster adapter matching both
             else → base language adapter only
        2. load base Whisper + warm-start adapter (peft.PeftModel)
        3. load calibration samples for session, preprocess (resample 16kHz, feature extraction)
        4. fine-tune LoRA adapter (see train_lora_whisper.py) — small epoch count, small LR,
           since data volume is tiny (30-50 samples)
        5. evaluate WER on a held-out subset of calibration samples (if available) or against
           a small fixed validation set per language
        6. save adapter weights → object storage, register AdapterRecordBackend, version+1
        7. update TrainingJob status=SUCCEEDED, resulting_adapter_id set
   → on failure at any step: status=FAILED, error_message populated, session remains usable
     with previous adapter version (never leave a user without a working adapter)
```

**Retraining trigger (corrections path):** a scheduled job checks, per user, whether `corrections WHERE synced=true AND used_in_retrain=false` exceeds a threshold (e.g. 20). If so, enqueue a `TrainingJob` with `trigger=CORRECTION_THRESHOLD`, warm-starting from the user's current adapter rather than from a cluster adapter.

---

## 7. Security & Privacy Requirements

- All adapter files and local DB tables containing transcripts/corrections are encrypted at rest using Android Keystore-backed keys (`security/KeystoreCrypto.kt`).
- Correction/audio sync to the backend is **opt-in, off by default**, gated by an explicit consent screen; the consent flag must be checked before any `POST /v1/corrections` call is made.
- Calibration audio uploaded during onboarding should be deleted from backend object storage after successful training (configurable retention, default: delete after 30 days or immediately post-training — pick one and state it).
- Caregiver linking requires explicit user-side approval (`PENDING_USER_APPROVAL` state) before any caregiver endpoint returns data.
- No raw audio is ever required for `AccessibilityService` interactions — it only reads/acts on UI view hierarchy, never audio or screen pixels.
- Bearer tokens are per-device, short-lived, refreshable; no permanent credentials stored in app code.

---

## 8. Repo Structure (for the build agent to scaffold)

```
/mobile-app/                   # React Native app + embedded native Android module, layout per §2.1
/training-backend/
  ├── app/
  │   ├── main.py               # FastAPI entrypoint
  │   ├── routers/
  │   │   ├── auth.py
  │   │   ├── calibration.py
  │   │   ├── adapters.py
  │   │   ├── corrections.py
  │   │   └── caregiver.py
  │   ├── models/                # Pydantic models, §4.2
  │   ├── db/                    # SQLAlchemy models + migrations
  │   ├── workers/
  │   │   └── train_worker.py    # wraps train_lora_whisper.py logic
  │   └── storage/                # object storage client (S3-compatible)
  ├── ml/
  │   └── train_lora_whisper.py  # from earlier deliverable, adapted for job-queue use
  └── requirements.txt
/caregiver-dashboard/           # React app (optional, can be deferred past hackathon MVP)
/docs/
  ├── dysarthric-asr-implementation.md      # (previous doc — product/architecture narrative)
  └── technical-implementation-spec.md      # this document
```

---

## 9. Build Priority for Agent (in order)

1. Backend: `auth`, `calibration` routers + DB models + object storage stub (local disk is fine for hackathon).
2. `ml/train_lora_whisper.py` wired into `train_worker.py`, callable synchronously first (async queue is a stretch goal).
3. Native Android: `AudioCaptureManager` + `WhisperInferenceEngine` (base model only, no adapter yet) — get raw STT working end-to-end **as a standalone native test harness first**, before wiring the RN bridge, to isolate NPU/inference bugs from bridge bugs.
4. Native Android: `SpeechModule.kt` + `RecognitionEventEmitter.kt` bridge scaffolding — the single highest-risk integration point in this stack; get a trivial "ping" method call and a trivial event round-tripping between RN and native working before adding real STT logic on top.
5. RN: `SpeechBridge.ts` wrapper + a minimal screen that calls `transcribeFile()` on a test audio file and displays the result — proves the bridge works end-to-end.
6. Native Android: `AdapterManager` + backend `adapters` router — wire personalization in, exposed to RN via `loadUserAdapter`/`loadLanguageAdapter`.
7. Native Android: `PersonalizedRecognitionService` registration — prove system-wide dictation works (this path bypasses RN entirely at runtime, so test it independently of the RN app being open).
8. Native Android: `IntentParser` + `ActionExecutor` (Android Intents path only; defer `AccessibilityActionService` fallback).
9. Native Android: `SpeakBackManager` (system TTS) for confirmation flow.
10. RN: `CalibrationScreen.tsx` full flow, `PhrasebookScreen.tsx` CRUD, wired to both the backend API and the native phrasebook-mirror push.
11. Corrections capture (RN-side store) + `/v1/corrections` sync (opt-in flow).
12. Caregiver dashboard + endpoints — only if time remains.

**Risk flag for the agent:** step 4 (the RN↔native bridge) is the most likely source of lost hackathon time if the team hasn't done RN native module development before. If the bridge proves unstable close to deadline, the fallback is to demo the native STT/action pipeline directly (e.g. via `adb` or a minimal native test Activity) while keeping the RN UI for calibration/settings only — don't let bridge debugging block the core STT demo.
