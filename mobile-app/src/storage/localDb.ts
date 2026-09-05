/**
 * VaaniMitra — Local database (AsyncStorage wrapper)
 * Stores: phrasebook entries, correction records, user settings, auth token.
 * The native Room DB mirror is updated via SpeechModule.syncPhrasebookEntry().
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { PhrasebookEntry, CorrectionRecord } from '../native/types';

// ── Keys ──────────────────────────────────────────────────────────────────────

const KEYS = {
  AUTH: 'vaani:auth',
  SETTINGS: 'vaani:settings',
  PHRASEBOOK: 'vaani:phrasebook',
  CORRECTIONS: 'vaani:corrections',
} as const;

// ── Auth ──────────────────────────────────────────────────────────────────────

export interface StoredAuth {
  userId: string;
  accessToken: string;
  expiresAt: string;
}

export const LocalDb = {
  // ── Auth ───────────────────────────────────────────────────────────────────

  async saveAuth(auth: StoredAuth): Promise<void> {
    await AsyncStorage.setItem(KEYS.AUTH, JSON.stringify(auth));
  },

  async loadAuth(): Promise<StoredAuth | null> {
    const raw = await AsyncStorage.getItem(KEYS.AUTH);
    return raw ? JSON.parse(raw) : null;
  },

  async clearAuth(): Promise<void> {
    await AsyncStorage.removeItem(KEYS.AUTH);
  },

  // ── Settings ───────────────────────────────────────────────────────────────

  async saveSettings(settings: Record<string, unknown>): Promise<void> {
    await AsyncStorage.setItem(KEYS.SETTINGS, JSON.stringify(settings));
  },

  async loadSettings(): Promise<Record<string, unknown> | null> {
    const raw = await AsyncStorage.getItem(KEYS.SETTINGS);
    return raw ? JSON.parse(raw) : null;
  },

  // ── Phrasebook ─────────────────────────────────────────────────────────────

  async loadPhrasebook(): Promise<PhrasebookEntry[]> {
    const raw = await AsyncStorage.getItem(KEYS.PHRASEBOOK);
    return raw ? JSON.parse(raw) : [];
  },

  async savePhrasebook(entries: PhrasebookEntry[]): Promise<void> {
    await AsyncStorage.setItem(KEYS.PHRASEBOOK, JSON.stringify(entries));
  },

  async addPhrasebookEntry(entry: PhrasebookEntry): Promise<void> {
    const existing = await LocalDb.loadPhrasebook();
    const updated = [...existing.filter(e => e.id !== entry.id), entry];
    await LocalDb.savePhrasebook(updated);
  },

  async deletePhrasebookEntry(id: string): Promise<void> {
    const existing = await LocalDb.loadPhrasebook();
    await LocalDb.savePhrasebook(existing.filter(e => e.id !== id));
  },

  // ── Corrections ────────────────────────────────────────────────────────────

  async loadCorrections(): Promise<CorrectionRecord[]> {
    const raw = await AsyncStorage.getItem(KEYS.CORRECTIONS);
    return raw ? JSON.parse(raw) : [];
  },

  async appendCorrection(record: CorrectionRecord): Promise<void> {
    const existing = await LocalDb.loadCorrections();
    await AsyncStorage.setItem(KEYS.CORRECTIONS, JSON.stringify([...existing, record]));
  },

  async markCorrectionsSynced(ids: string[]): Promise<void> {
    const existing = await LocalDb.loadCorrections();
    const updated = existing.map(r =>
      ids.includes(r.id) ? { ...r, syncedToBackend: true } : r,
    );
    await AsyncStorage.setItem(KEYS.CORRECTIONS, JSON.stringify(updated));
  },

  async getUnsyncedCorrections(): Promise<CorrectionRecord[]> {
    const all = await LocalDb.loadCorrections();
    return all.filter(r => !r.syncedToBackend);
  },
};
