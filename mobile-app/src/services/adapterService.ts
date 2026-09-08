/**
 * VaaniMitra — Adapter download, load, and persistence helpers.
 */
import { backendClient } from '../api/trainingBackendClient';
import { SpeechBridge } from '../native/SpeechBridge';
import { LocalDb } from '../storage/localDb';
import type { AdapterHandle } from '../native/types';

const API_BASE = __DEV__
  ? 'http://10.0.2.2:8000'
  : 'https://your-production-server.example.com';

function resolveDownloadUrl(url: string): string {
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  return `${API_BASE}${url.startsWith('/') ? '' : '/'}${url}`;
}

function getAuthToken(): string {
  const token = backendClient.getToken();
  if (!token) throw new Error('Not authenticated — cannot download adapter');
  return token;
}

export async function downloadAndLoadClusterAdapter(
  language: string,
  severity?: string,
): Promise<AdapterHandle> {
  const cluster = await backendClient.adapters.getClusterAdapter(language, severity);
  const handle = await SpeechBridge.downloadAndLoadClusterAdapter(
    resolveDownloadUrl(cluster.download_url),
    getAuthToken(),
    language,
    cluster.adapter_id,
    cluster.version,
  );
  await LocalDb.saveActiveAdapters([handle]);
  return handle;
}

export async function downloadAndLoadUserAdapter(
  userId: string,
  adapterId: string,
  version: number,
): Promise<AdapterHandle> {
  const handle = await SpeechBridge.downloadAndLoadUserAdapter(
    resolveDownloadUrl(backendClient.adapters.getDownloadUrl(adapterId)),
    getAuthToken(),
    userId,
    version,
  );
  const existing = await LocalDb.loadActiveAdapters();
  const merged = [
    ...existing.filter(a => a.type !== 'USER'),
    handle,
  ];
  await LocalDb.saveActiveAdapters(merged);
  return handle;
}

export async function restoreAdaptersOnBoot(): Promise<AdapterHandle[]> {
  try {
    const handles = await SpeechBridge.restorePersistedAdapters();
    if (handles.length > 0) {
      await LocalDb.saveActiveAdapters(handles);
      return handles;
    }
  } catch (e) {
    console.warn('[adapterService] Native restore failed:', e);
  }

  const cached = await LocalDb.loadActiveAdapters();
  if (cached.length === 0) return [];

  try {
    const handles = await SpeechBridge.getCurrentAdapterInfo();
    if (handles.length > 0) return handles;
  } catch (_) {}

  return cached;
}

export async function syncPhrasebookToNative(entries: unknown[]): Promise<void> {
  try {
    await SpeechBridge.syncPhrasebookBulk(JSON.stringify(entries));
  } catch (e) {
    console.warn('[adapterService] Phrasebook sync failed:', e);
  }
}
