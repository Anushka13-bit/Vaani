/**
 * VaaniMitra — Zustand store (root)
 * Combines adapter state and phrasebook state.
 */
import { create } from 'zustand';
import type { AdapterHandle, PhrasebookEntry, WakeWordEvent } from '../native/types';

// ── Adapter slice ─────────────────────────────────────────────────────────────

interface AdapterState {
  activeAdapters: AdapterHandle[];
  isLoading: boolean;
  error: string | null;
  setActiveAdapters: (adapters: AdapterHandle[]) => void;
  setLoading: (loading: boolean) => void;
  setError: (error: string | null) => void;
}

// ── Phrasebook slice ──────────────────────────────────────────────────────────

interface PhrasebookState {
  entries: PhrasebookEntry[];
  setEntries: (entries: PhrasebookEntry[]) => void;
  addEntry: (entry: PhrasebookEntry) => void;
  updateEntry: (entry: PhrasebookEntry) => void;
  removeEntry: (id: string) => void;
}

// ── Auth slice ────────────────────────────────────────────────────────────────

interface AuthState {
  userId: string | null;
  accessToken: string | null;
  setAuth: (userId: string, token: string) => void;
  clearAuth: () => void;
}

// ── Settings slice ────────────────────────────────────────────────────────────

interface SettingsState {
  preferredLanguage: string;
  dysarthriaSeverityHint: string | null;
  correctionSyncOptIn: boolean;  // user consent for cloud correction sync
  setPreferredLanguage: (lang: string) => void;
  setSeverityHint: (hint: string | null) => void;
  setCorrectionSyncOptIn: (optIn: boolean) => void;
}

// ── Wake Word slice ───────────────────────────────────────────────────────────

interface WakeWordState {
  wakeWordEnabled: boolean;
  wakeWordListening: boolean;
  lastWakeWordEvent: WakeWordEvent | null;
  wakeWordStopReason: string | null;
  setWakeWordEnabled: (enabled: boolean) => void;
  setWakeWordListening: (listening: boolean) => void;
  setLastWakeWordEvent: (event: WakeWordEvent | null) => void;
  setWakeWordStopReason: (reason: string | null) => void;
}

// ── Combined store ────────────────────────────────────────────────────────────

type AppStore = AdapterState & PhrasebookState & AuthState & SettingsState & WakeWordState;

export const useStore = create<AppStore>((set) => ({
  // Adapter
  activeAdapters: [],
  isLoading: false,
  error: null,
  setActiveAdapters: (adapters) => set({ activeAdapters: adapters }),
  setLoading: (loading) => set({ isLoading: loading }),
  setError: (error) => set({ error }),

  // Phrasebook
  entries: [],
  setEntries: (entries) => set({ entries }),
  addEntry: (entry) => set((state) => ({ entries: [...state.entries, entry] })),
  updateEntry: (entry) =>
    set((state) => ({
      entries: state.entries.map((e) => (e.id === entry.id ? entry : e)),
    })),
  removeEntry: (id) =>
    set((state) => ({ entries: state.entries.filter((e) => e.id !== id) })),

  // Auth
  userId: null,
  accessToken: null,
  setAuth: (userId, accessToken) => set({ userId, accessToken }),
  clearAuth: () => set({ userId: null, accessToken: null }),

  // Settings
  preferredLanguage: 'en',
  dysarthriaSeverityHint: null,
  correctionSyncOptIn: false,
  setPreferredLanguage: (lang) => set({ preferredLanguage: lang }),
  setSeverityHint: (hint) => set({ dysarthriaSeverityHint: hint }),
  setCorrectionSyncOptIn: (optIn) => set({ correctionSyncOptIn: optIn }),

  // Wake Word
  wakeWordEnabled: false,
  wakeWordListening: false,
  lastWakeWordEvent: null,
  wakeWordStopReason: null,
  setWakeWordEnabled: (wakeWordEnabled) => set({ wakeWordEnabled }),
  setWakeWordListening: (wakeWordListening) => set({ wakeWordListening }),
  setLastWakeWordEvent: (lastWakeWordEvent) => set({ lastWakeWordEvent }),
  setWakeWordStopReason: (wakeWordStopReason) => set({ wakeWordStopReason }),
}));
