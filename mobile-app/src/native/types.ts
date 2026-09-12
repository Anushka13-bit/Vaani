/**
 * VaaniMitra — Shared TypeScript types mirroring native Kotlin data classes (§2.2)
 * These types cross the RN ↔ Native bridge.
 */

// ── Adapter ───────────────────────────────────────────────────────────────────

export type AdapterType = 'USER' | 'LANGUAGE' | 'CLUSTER';

export interface AdapterHandle {
  adapterId: string;
  version: number;
  type: AdapterType;
  filePath: string;
  checksum: string;
}

// ── Transcription ─────────────────────────────────────────────────────────────

export interface TranscriptSegment {
  text: string;
  startMs: number;
  endMs: number;
  confidence: number; // 0.0 – 1.0
}

export interface TranscriptionResult {
  text: string;
  languageDetected: string; // e.g. "en", "ta", "ta-en"
  segments: TranscriptSegment[];
}

// ── Transcribe + act (manual capture) ────────────────────────────────────────

export type TranscriptOutcomeKind = 'action_executed' | 'dictated' | 'cancelled' | 'ignored';

/**
 * Result of SpeechBridge.stopManualCaptureAndTranscribe() — a transcript on its own
 * (TranscriptionResult) doesn't say whether a command was actually run. Mirrors
 * VoicePipeline.TranscriptOutcome on the native side.
 */
export interface TranscribeAndActResult {
  text: string;
  confidence: number;
  languageDetected: string;
  executionProvider: string;
  outcome: TranscriptOutcomeKind;
  /** Present when outcome === 'action_executed'. */
  action?: ActionType;
  actionSuccess?: boolean;
  actionMessage?: string;
  /** Present when outcome === 'cancelled' | 'ignored'. */
  reason?: string;
}

// ── Intents & Actions ─────────────────────────────────────────────────────────

export type ActionType =
  | 'DICTATE_TEXT'
  | 'SEND_MESSAGE'
  | 'SET_REMINDER'
  | 'SET_ALARM'
  | 'PLACE_CALL'
  | 'WEB_SEARCH'
  | 'OPEN_APP'
  | 'SMART_HOME_ACTION'
  | 'UNKNOWN';

export interface ParsedIntent {
  action: ActionType;
  entities: Record<string, string>;
  confidence: number;
  requiresConfirmation: boolean;
}

export interface ActionResult {
  success: boolean;
  message: string;
  requiresAccessibilityFallback: boolean;
}

// ── Phrasebook ────────────────────────────────────────────────────────────────

export interface PhrasebookEntry {
  id: string;
  userId: string;
  triggerPhrase: string;
  actionType: ActionType;
  actionPayloadJson: string;
  createdAt: number; // epoch ms
  lastUsedAt: number | null;
  useCount: number;
}

// ── Corrections ───────────────────────────────────────────────────────────────

export interface CorrectionRecord {
  id: string;
  userId: string;
  originalTranscript: string;
  correctedTranscript: string;
  audioFilePath: string | null;
  confidenceAtTime: number;
  createdAt: number;
  syncedToBackend: boolean;
}

// ── Wake Word ─────────────────────────────────────────────────────────────────

export interface WakeWordEvent {
  model: string;
  score: number;
  timestamp: number;
}

// ── Calibration ───────────────────────────────────────────────────────────────

export interface CalibrationRecordResult {
  sessionId: string;
  phraseIndex: number;
  fileName: string;
  filePath: string;
  fileSize: number;
  promptText: string;
  manifestPath: string;
}

export interface CalibrationFilesInfo {
  sessionId: string;
  clips: Array<{ name: string; path: string; size: number }>;
  clipCount: number;
  hasManifest: boolean;
  manifestPath: string;
}


