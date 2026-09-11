/**
 * VaaniMitra — Training Backend HTTP Client
 * Typed axios client for all backend API endpoints (§5).
 *
 * Usage:
 *   import { backendClient } from './trainingBackendClient';
 *   const token = await backendClient.auth.registerDevice({ device_id: 'abc', preferred_language: 'en' });
 */
import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';
import { API_BASE_URL } from '../config/backend';
import type {
  AdapterMetaResponse,
  CaregiverLinkRequest,
  CaregiverLinkResponse,
  ClusterAdapterResponse,
  CorrectionItem,
  CorrectionsUploadResponse,
  CreateSessionRequest,
  CreateSessionResponse,
  DeviceRegisterRequest,
  DeviceRegisterResponse,
  PhrasebookUpdateRequest,
  PhrasebookUpdateResponse,
  PromptSetResponse,
  SampleUploadResponse,
  SessionAdapterStatusResponse,
  SessionStatusResponse,
  TranscriptListResponse,
  UserAdapterResponse,
} from './dto';

// ── Config ────────────────────────────────────────────────────────────────────
// See src/config/backend.ts — use adb reverse for USB physical device.

const BASE_URL = API_BASE_URL;

// ── Client factory ────────────────────────────────────────────────────────────

class TrainingBackendClient {
  private http: AxiosInstance;
  private token: string | null = null;

  constructor(baseURL: string) {
    this.http = axios.create({ baseURL, timeout: 30_000 });

    // Attach bearer token to every request
    this.http.interceptors.request.use(config => {
      if (this.token) {
        config.headers = config.headers ?? {};
        config.headers['Authorization'] = `Bearer ${this.token}`;
      }
      return config;
    });
  }

  setToken(token: string): void {
    this.token = token;
  }

  getToken(): string | null {
    return this.token;
  }

  // ── Auth ────────────────────────────────────────────────────────────────────

  auth = {
    registerDevice: async (body: DeviceRegisterRequest): Promise<DeviceRegisterResponse> => {
      const { data } = await this.http.post<DeviceRegisterResponse>('/auth/device', body);
      this.setToken(data.access_token);
      return data;
    },
  };

  // ── Calibration ─────────────────────────────────────────────────────────────

  calibration = {
    getBaseUrl: (): string => BASE_URL,

    getPrompts: async (language = 'en', count = 40): Promise<PromptSetResponse> => {
      const { data } = await this.http.get<PromptSetResponse>('/calibration/prompts', {
        params: { language, count },
      });
      return data;
    },

    uploadBatch: async (
      sessionId: string,
      manifestJson: string,
      files: Array<{ uri: string; name: string; type?: string }>,
    ): Promise<any> => {
      const form = new FormData();
      form.append('session_id', sessionId);
      form.append('manifest_json', manifestJson);
      for (const file of files) {
        form.append('files', {
          uri: file.uri,
          name: file.name,
          type: file.type || 'audio/wav',
        } as any);
      }
      const { data } = await this.http.post('/calibrate', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 120_000,
      });
      return data;
    },

    createSession: async (body: CreateSessionRequest): Promise<CreateSessionResponse> => {
      const { data } = await this.http.post<CreateSessionResponse>('/calibration/sessions', body);
      return data;
    },

    uploadSample: async (
      sessionId: string,
      audioUri: string,
      promptId: string,
      mimeType = 'audio/wav',
    ): Promise<SampleUploadResponse> => {
      const form = new FormData();
      form.append('audio', { uri: audioUri, type: mimeType, name: 'sample.wav' } as any);
      form.append('prompt_id', promptId);
      const { data } = await this.http.post<SampleUploadResponse>(
        `/calibration/sessions/${sessionId}/samples`,
        form,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      );
      return data;
    },

    triggerTraining: async (sessionId: string): Promise<void> => {
      // NOTE: Returns 501 while live training is disabled.
      // The app should handle this gracefully and redirect the user to use
      // the cluster adapter directly via getClusterAdapter().
      await this.http.post(`/calibration/sessions/${sessionId}/train`);
    },

    getStatus: async (sessionId: string): Promise<SessionStatusResponse> => {
      const { data } = await this.http.get<SessionStatusResponse>(
        `/calibration/sessions/${sessionId}/status`,
      );
      return data;
    },

    getSessionAdapterStatus: async (sessionId: string): Promise<SessionAdapterStatusResponse> => {
      const { data } = await this.http.get<SessionAdapterStatusResponse>(
        `/adapter/${sessionId}/status`,
      );
      return data;
    },

    getSessionAdapterDownloadUrl: (sessionId: string): string =>
      `${BASE_URL}/adapter/${sessionId}`,
  };

  // ── Adapters ─────────────────────────────────────────────────────────────────

  adapters = {
    getMeta: async (adapterId: string): Promise<AdapterMetaResponse> => {
      const { data } = await this.http.get<AdapterMetaResponse>(`/adapters/${adapterId}`);
      return data;
    },

    getDownloadUrl: (adapterId: string): string =>
      `${BASE_URL}/adapters/${adapterId}/download`,

    getMobileBundleUrl: (adapterId: string): string =>
      `${BASE_URL}/adapters/${adapterId}/mobile`,

    getClusterAdapter: async (language: string, severity?: string): Promise<ClusterAdapterResponse> => {
      const { data } = await this.http.get<ClusterAdapterResponse>('/adapters/clusters', {
        params: { language, ...(severity ? { severity } : {}) },
      });
      return data;
    },

    getUserAdapter: async (userId: string): Promise<UserAdapterResponse> => {
      const { data } = await this.http.get<UserAdapterResponse>(`/users/${userId}/adapter`);
      return data;
    },
  };

  // ── Corrections ───────────────────────────────────────────────────────────────

  corrections = {
    upload: async (
      userId: string,
      corrections: CorrectionItem[],
    ): Promise<CorrectionsUploadResponse> => {
      const { data } = await this.http.post<CorrectionsUploadResponse>('/corrections', {
        user_id: userId,
        corrections,
      });
      return data;
    },
  };

  // ── Caregiver ─────────────────────────────────────────────────────────────────

  caregiver = {
    linkCaregiver: async (body: CaregiverLinkRequest): Promise<CaregiverLinkResponse> => {
      const { data } = await this.http.post<CaregiverLinkResponse>('/caregiver/link', body);
      return data;
    },

    getTranscripts: async (
      userId: string,
      confidenceLt = 0.6,
      limit = 50,
    ): Promise<TranscriptListResponse> => {
      const { data } = await this.http.get<TranscriptListResponse>(
        `/caregiver/${userId}/transcripts`,
        { params: { confidence_lt: confidenceLt, limit } },
      );
      return data;
    },

    updatePhrasebook: async (
      userId: string,
      entryId: string,
      body: PhrasebookUpdateRequest,
    ): Promise<PhrasebookUpdateResponse> => {
      const { data } = await this.http.put<PhrasebookUpdateResponse>(
        `/caregiver/${userId}/phrasebook/${entryId}`,
        body,
      );
      return data;
    },
  };
}

export const backendClient = new TrainingBackendClient(BASE_URL);
