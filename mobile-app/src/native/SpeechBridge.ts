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

  downloadAndLoadMobileBundle: (
    downloadUrl: string,
    authToken: string,
    adapterId: string,
    version: number,
    adapterType: 'USER' | 'CLUSTER' | 'LANGUAGE',
  ): Promise<AdapterHandle> =>
    SpeechModule.downloadAndLoadMobileBundle(
      downloadUrl,
      authToken,
      adapterId,
      version,
      adapterType,
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

  startCalibrationRecording: (
    sessionId: string,
    phraseIndex: number,
    promptText: string,
  ): Promise<boolean> =>
    SpeechModule.startCalibrationRecording(sessionId, phraseIndex, promptText),

  stopCalibrationRecording: (
    sessionId: string,
    phraseIndex: number,
    promptText: string,
  ): Promise<{
    sessionId: string;
    phraseIndex: number;
    fileName: string;
    filePath: string;
    fileSize: number;
    promptText: string;
    manifestPath: string;
  }> =>
    SpeechModule.stopCalibrationRecording(sessionId, phraseIndex, promptText),

  uploadCalibrationBatch: (
    sessionId: string,
    baseUrl: string,
    authToken = '',
  ): Promise<string> =>
    SpeechModule.uploadCalibrationBatch(sessionId, baseUrl, authToken),

  getCalibrationSessionFiles: (
    sessionId: string,
  ): Promise<{
    sessionId: string;
    clips: Array<{ name: string; path: string; size: number }>;
    clipCount: number;
    hasManifest: boolean;
    manifestPath: string;
  }> =>
    SpeechModule.getCalibrationSessionFiles(sessionId),

  onTranscriptSegment: (
    callback: (segment: TranscriptSegment) => void,
  ): EmitterSubscription | null => {
    if (!recognitionEvents) {
      console.warn('[SpeechBridge] RecognitionEventEmitter not available.');
      return null;
    }
    return recognitionEvents.addListener('onTranscriptSegment', callback);
  },

  onConfirmationRequired: (
    callback: (payload: { text: string; intentJson: string }) => void,
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
