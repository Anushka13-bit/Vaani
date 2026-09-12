/**
 * VaaniMitra — Adapter download, load, and persistence helpers.
 */
import { backendClient } from '../api/trainingBackendClient';
import { SpeechBridge } from '../native/SpeechBridge';
import { LocalDb } from '../storage/localDb';
import type { AdapterHandle } from '../native/types';

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
  const handle = await SpeechBridge.downloadAndLoadMobileBundle(
    backendClient.adapters.getMobileBundleUrl(cluster.adapter_id),
    getAuthToken(),
    cluster.adapter_id,
    cluster.version,
    'CLUSTER',
  );
  await LocalDb.saveActiveAdapters([handle]);
  return handle;
}

/**
 * Download per-user ONNX mobile bundle produced by local fine-tune.
 * `referenceAudioPath` (typically the phone's last calibration recording) triggers a
 * real before/after transcription check on-device — if the transcript comes back
 * unchanged, or execution silently fell back off the NPU, that's logged loudly here
 * rather than reported as a silent success.
 */
export async function downloadAndLoadUserAdapter(
  userId: string,
  adapterId: string,
  version: number,
  sessionId?: string,
  referenceAudioPath?: string,
): Promise<AdapterHandle> {
  const downloadUrl = sessionId
    ? backendClient.calibration.getSessionAdapterDownloadUrl(sessionId)
    : backendClient.adapters.getMobileBundleUrl(adapterId);

  const handle = await SpeechBridge.downloadAndLoadMobileBundle(
    downloadUrl,
    getAuthToken(),
    adapterId,
    version,
    'USER',
    referenceAudioPath,
  );

  if (handle.verified) {
    if (!handle.transcriptChanged) {
      console.warn(
        '[adapterService] Adapter verification: transcript UNCHANGED after loading',
        adapterId,
        '— personalization may not actually be applied. text=',
        handle.newText,
      );
    }
    if (!handle.usedNpuAfterSwap) {
      console.warn(
        '[adapterService] Adapter verification: post-swap inference ran on',
        handle.newExecutionProvider,
        '- expected NNAPI/NPU.',
      );
    }
  }

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
