/**
 * VaaniMitra — SpeechBridge (§2.2)
 * Typed RN-side wrapper around the native SpeechModule + RecognitionEventEmitter.
 *
 * Native side: android/.../bridge/SpeechModule.kt
 *              android/.../bridge/RecognitionEventEmitter.kt
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
  /**
   * Load a per-user LoRA adapter into the native inference engine.
   * Resolves with an AdapterHandle on success.
   */
  loadUserAdapter: (userId: string): Promise<AdapterHandle> =>
    SpeechModule.loadUserAdapter(userId),

  /**
   * Load a language/cluster adapter by BCP-47 language code.
   */
  loadLanguageAdapter: (languageCode: string): Promise<AdapterHandle> =>
    SpeechModule.loadLanguageAdapter(languageCode),

  /**
   * Transcribe a local audio file. Used for calibration review / before-after demos.
   * Live streaming dictation goes through PersonalizedRecognitionService (not this method).
   */
  transcribeFile: (audioFilePath: string): Promise<TranscriptionResult> =>
    SpeechModule.transcribeFile(audioFilePath),

  /**
   * Returns metadata about all currently stacked (active) adapters.
   */
  getCurrentAdapterInfo: (): Promise<AdapterHandle[]> =>
    SpeechModule.getCurrentAdapterInfo(),

  /**
   * Subscribe to live transcript segments streamed from PersonalizedRecognitionService.
   * Each segment includes text, confidence, and start timestamp.
   *
   * @returns EmitterSubscription — call .remove() to unsubscribe.
   */
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
   * Subscribe to action confirmation requests from the native layer.
   */
  onConfirmationRequired: (
    callback: (payload: { text: string; intentJson: string }) => void,
  ): EmitterSubscription | null => {
    if (!recognitionEvents) return null;
    return recognitionEvents.addListener('onConfirmationRequired', callback);
  },
};
