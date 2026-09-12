/**
 * VaaniMitra — SpeechBridge (§2.2)
 */
import { NativeModules, NativeEventEmitter, EmitterSubscription } from 'react-native';
import type { AdapterHandle, TranscriptSegment, TranscriptionResult } from './types';

const { SpeechModule, RecognitionEventEmitter: RecognitionEventEmitterModule } = NativeModules;

if (!SpeechModule) {
  console.warn(
    '[SpeechBridge] SpeechModule native module not found. ' +
    'Ensure the native Android build includes SpeechModulePackage and you ran react-native run-android.',
  );
}

const recognitionEvents = RecognitionEventEmitterModule
  ? new NativeEventEmitter(RecognitionEventEmitterModule)
  : null;

export const SpeechBridge = {
  loadUserAdapter: (userId: string): Promise<AdapterHandle> =>
    SpeechModule.loadUserAdapter(userId),

  loadLanguageAdapter: (languageCode: string): Promise<AdapterHandle> =>
    SpeechModule.loadLanguageAdapter(languageCode),

  /**
   * `referenceAudioPath`: when set for a USER adapter, the native side runs a real
   * before/after transcription comparison on that clip (16kHz mono PCM WAV) as part of
   * the swap, and the result carries `verified`/`transcriptChanged`/`usedNpuAfterSwap`.
   * Pass '' to skip verification (e.g. for CLUSTER/LANGUAGE loads with no reference clip).
   */
  downloadAndLoadMobileBundle: (
    downloadUrl: string,
    authToken: string,
    adapterId: string,
    version: number,
    adapterType: 'USER' | 'CLUSTER' | 'LANGUAGE',
    referenceAudioPath?: string,
  ): Promise<AdapterHandle & {
    verified?: boolean;
    transcriptChanged?: boolean;
    previousText?: string;
    newText?: string;
    previousExecutionProvider?: string;
    newExecutionProvider?: string;
    usedNpuAfterSwap?: boolean;
  }> =>
    SpeechModule.downloadAndLoadMobileBundle(
      downloadUrl,
      authToken,
      adapterId,
      version,
      adapterType,
      referenceAudioPath ?? '',
    ),

  downloadAndLoadClusterAdapter: (
    downloadUrl: string,
    authToken: string,
    languageCode: string,
    serverAdapterId: string,
    version: number,
  ): Promise<AdapterHandle> =>
    SpeechModule.downloadAndLoadClusterAdapter(
      downloadUrl,
      authToken,
      languageCode,
      serverAdapterId,
      version,
    ),

  downloadAndLoadUserAdapter: (
    downloadUrl: string,
    authToken: string,
    userId: string,
    version: number,
  ): Promise<AdapterHandle> =>
    SpeechModule.downloadAndLoadUserAdapter(downloadUrl, authToken, userId, version),

  restorePersistedAdapters: (): Promise<AdapterHandle[]> =>
    SpeechModule.restorePersistedAdapters(),

  transcribeFile: (audioFilePath: string): Promise<TranscriptionResult> =>
    SpeechModule.transcribeFile(audioFilePath),

  /**
   * Raw AudioRecord capture — the same on-device pipeline VoicePipeline uses for
   * wake-word-triggered dictation (AudioCaptureManager + VoiceActivityDetector +
   * WhisperInferenceEngine). Use this pair for a "tap mic and speak" UI instead of
   * recording to a file and calling transcribeFile(): react-native-audio-recorder-player
   * records AAC/M4A on Android, which the native STT stack has no decoder for.
   */
  startManualCapture: (): Promise<boolean> => SpeechModule.startManualCapture(),

  stopManualCaptureAndTranscribe: (): Promise<TranscriptionResult> =>
    SpeechModule.stopManualCaptureAndTranscribe(),

  getCurrentAdapterInfo: (): Promise<AdapterHandle[]> =>
    SpeechModule.getCurrentAdapterInfo(),

  syncPhrasebookEntry: (entryJson: string): Promise<boolean> =>
    SpeechModule.syncPhrasebookEntry(entryJson),

  syncPhrasebookBulk: (entriesJson: string): Promise<boolean> =>
    SpeechModule.syncPhrasebookBulk(entriesJson),

  removePhrasebookEntry: (triggerPhrase: string): Promise<boolean> =>
    SpeechModule.removePhrasebookEntry(triggerPhrase),

  openVoiceInputSettings: (): Promise<boolean> =>
    SpeechModule.openVoiceInputSettings(),

  startWakeWordService: (): Promise<boolean> =>
    SpeechModule.startWakeWordService(),

  stopWakeWordService: (): Promise<boolean> =>
    SpeechModule.stopWakeWordService(),

  isWakeWordServiceRunning: (): Promise<boolean> =>
    SpeechModule.isWakeWordServiceRunning(),

  checkVoicePermissions: (): Promise<boolean> =>
    SpeechModule.checkVoicePermissions(),

  getWakeWordStopReason: (): Promise<string> =>
    SpeechModule.getWakeWordStopReason(),

  onTranscriptSegment: (
    callback: (segment: TranscriptSegment) => void,
  ): EmitterSubscription | null => {
    if (!recognitionEvents) {
      console.warn('[SpeechBridge] RecognitionEventEmitter not available.');
      return null;
    }
    return recognitionEvents.addListener('onTranscriptSegment', callback);
  },

  /**
   * `audioPath`/`confidence`: optional so this stays backward-compatible with a
   * native build that hasn't been updated yet, but required for a fired
   * confirmation to ever become a correction that can retrain the acoustic
   * model (see VoicePipeline — the clip must be written to a file and its path
   * included here, not just held in memory and discarded).
   */
  onConfirmationRequired: (
    callback: (payload: {
      text: string;
      intentJson: string;
      audioPath?: string;
      confidence?: number;
    }) => void,
  ): EmitterSubscription | null => {
    if (!recognitionEvents) return null;
    return recognitionEvents.addListener('onConfirmationRequired', callback);
  },

  onWakeWordDetected: (
    callback: (payload: { model: string; score: number }) => void,
  ): EmitterSubscription | null => {
    if (!recognitionEvents) {
      console.warn('[SpeechBridge] RecognitionEventEmitter not available.');
      return null;
    }
    return recognitionEvents.addListener('onWakeWordDetected', callback);
  },

  onWakeWordError: (
    callback: (payload: { reason: string }) => void,
  ): EmitterSubscription | null => {
    if (!recognitionEvents) {
      console.warn('[SpeechBridge] RecognitionEventEmitter not available.');
      return null;
    }
    return recognitionEvents.addListener('onWakeWordError', callback);
  },
};
