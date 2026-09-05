/**
 * VaaniMitra — Backend API DTO types (TypeScript mirror of Pydantic models §4.2 / §5)
 */

// ── Auth ──────────────────────────────────────────────────────────────────────

export interface DeviceRegisterRequest {
  device_id: string;
  preferred_language: string;
  dysarthria_severity_hint?: string;
}

export interface DeviceRegisterResponse {
  user_id: string;
  access_token: string;
  expires_at: string; // ISO 8601
}

// ── Calibration ───────────────────────────────────────────────────────────────

export interface Prompt {
  prompt_id: string;
  text: string;
  language: string;
}

export interface PromptSetResponse {
  prompt_set_id: string;
  prompts: Prompt[];
}

export interface CreateSessionRequest {
  user_id: string;
  prompt_set_id: string;
}

export interface CreateSessionResponse {
  session_id: string;
  status: string;
  samples_required: number;
}

export interface SampleUploadResponse {
  sample_id: string;
  samples_received: number;
  samples_required: number;
}

export type SessionStatus = 'CREATED' | 'COLLECTING' | 'TRAINING' | 'COMPLETE' | 'FAILED';

export interface SessionStatusResponse {
  status: SessionStatus;
  job_id: string | null;
  progress_pct: number;
  resulting_adapter_id: string | null;
}

// ── Adapters ──────────────────────────────────────────────────────────────────

export interface AdapterMetaResponse {
  adapter_id: string;
  type: 'USER' | 'LANGUAGE' | 'CLUSTER';
  language_code: string;
  severity_cluster: string | null;
  version: number;
  checksum: string;
  download_url: string;
}

export interface ClusterAdapterResponse {
  adapter_id: string;
  version: number;
  download_url: string;
}

export interface UserAdapterResponse {
  adapter_id: string;
  version: number;
  updated_at: string;
}

// ── Corrections ───────────────────────────────────────────────────────────────

export interface CorrectionItem {
  original_transcript: string;
  corrected_transcript: string;
  confidence_at_time: number;
  audio_included: boolean;
}

export interface CorrectionsUploadRequest {
  user_id: string;
  corrections: CorrectionItem[];
}

export interface CorrectionsUploadResponse {
  accepted: number;
  retrain_triggered: boolean;
}

// ── Caregiver ─────────────────────────────────────────────────────────────────

export interface CaregiverLinkRequest {
  user_id: string;
  caregiver_email: string;
  permissions: Array<'VIEW_TRANSCRIPTS' | 'EDIT_PHRASEBOOK'>;
}

export interface CaregiverLinkResponse {
  caregiver_id: string;
  status: string;
}

export interface TranscriptEntry {
  id: string;
  transcript: string;
  confidence: number;
  timestamp: string;
}

export interface TranscriptListResponse {
  transcripts: TranscriptEntry[];
}

export interface PhrasebookUpdateRequest {
  trigger_phrase: string;
  action_type: string;
  action_payload: Record<string, unknown>;
}

export interface PhrasebookUpdateResponse {
  entry_id: string;
  updated: boolean;
}

// ── Error ─────────────────────────────────────────────────────────────────────

export interface ApiError {
  error: {
    code: string;
    message: string;
    request_id: string;
  };
}
