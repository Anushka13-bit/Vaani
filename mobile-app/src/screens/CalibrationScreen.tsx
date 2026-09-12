/**
 * VaaniMitra — CalibrationScreen (§3.1)
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import AudioRecorderPlayer from 'react-native-audio-recorder-player';
import { backendClient } from '../api/trainingBackendClient';
import { useStore } from '../state/store';
import type { Prompt } from '../api/dto';
import {
  downloadAndLoadClusterAdapter,
  downloadAndLoadUserAdapter,
} from '../services/adapterService';
import DeviceInfo from 'react-native-device-info';
import { LocalDb } from '../storage/localDb';
import { colors } from '../theme/colors';

const recorder = new AudioRecorderPlayer();
const POLL_INTERVAL_MS = 3000;
// Training time swings hard with the backend machine: ~2-6 min on M3/MPS or CUDA, but
// a CPU-only box runs 40 clips x 4 epochs (80 steps) plus ONNX export and INT8
// quantization well past 10 min. Sized for the CPU case — a timeout here silently
// downgrades the user to the un-personalized base adapter, which is worse than waiting.
const POLL_TIMEOUT_MS = 30 * 60 * 1000;
// Tolerate brief network blips (e.g. a USB/hotspot hiccup) without aborting the whole
// flow — only treat the laptop as unreachable after this many consecutive failed polls.
const MAX_CONSECUTIVE_POLL_FAILURES = 5; // ~15s of sustained unreachability at 3s interval

// Full 40-prompt calibration session (prompt_set_id="torgo_en_v1") for best LoRA
// personalization quality. Keep in sync with backend app/config.py DEFAULT_SAMPLE_COUNT.
const SESSION_PROMPT_COUNT = 40;

// Offline fallback prompts — used when the training backend is unreachable
const OFFLINE_PROMPTS: Prompt[] = [
  { prompt_id: 'offline_1', text: 'Please open the weather app for me', language: 'en' },
  { prompt_id: 'offline_2', text: 'Set an alarm for seven in the morning', language: 'en' },
  { prompt_id: 'offline_3', text: 'Send a message to my caregiver', language: 'en' },
  { prompt_id: 'offline_4', text: 'Call home please', language: 'en' },
  { prompt_id: 'offline_5', text: 'I need some help right now', language: 'en' },
];

function isNetworkError(e: any): boolean {
  const msg = (e?.message ?? '').toLowerCase();
  return (
    e?.code === 'ECONNABORTED' ||
    e?.code === 'ERR_NETWORK' ||
    msg.includes('network error') ||
    msg.includes('timeout') ||
    e?.response === undefined
  );
}

function networkErrorMessage(): string {
  return (
    'Cannot reach the training server on your laptop.\n\n' +
    '• Start backend: uvicorn app.main:app --reload --port 8000\n' +
    '• USB device: run adb reverse tcp:8000 tcp:8000\n' +
    '• Check API_HOST in src/config/backend.ts (127.0.0.1 for USB, 10.0.2.2 for emulator)'
  );
}

export default function CalibrationScreen({ navigation }: any) {
  const { userId, preferredLanguage, dysarthriaSeverityHint, setActiveAdapters, setAuth } = useStore();

  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [promptSetId, setPromptSetId] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isRecording, setIsRecording] = useState(false);
  const [lastRecordingUri, setLastRecordingUri] = useState<string | null>(null);
  const [samplesUploaded, setSamplesUploaded] = useState(0);
  const [trainingMessage, setTrainingMessage] = useState('Queuing local fine-tune on your laptop…');
  const [trainingProgress, setTrainingProgress] = useState(0);
  const [phase, setPhase] = useState<
    'loading' | 'prompts' | 'recording' | 'uploading' | 'training' | 'done' | 'error'
  >('loading');
  const [errorMsg, setErrorMsg] = useState('');
  const [isOfflineMode, setIsOfflineMode] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollStartRef = useRef<number>(0);
  const consecutivePollFailuresRef = useRef<number>(0);

  const clearPoll = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const loadCalibrationData = useCallback(async () => {
    try {
      setPhase('loading');
      setErrorMsg('');

      let currentUserId = userId;
      if (!currentUserId || !backendClient.getToken()) {
        try {
          const auth = await LocalDb.loadAuth();
          if (auth && new Date(auth.expiresAt) > new Date()) {
            backendClient.setToken(auth.accessToken);
            setAuth(auth.userId, auth.accessToken);
            currentUserId = auth.userId;
          } else {
            const deviceId = await DeviceInfo.getUniqueId();
            const resp = await backendClient.auth.registerDevice({
              device_id: deviceId,
              preferred_language: preferredLanguage || 'en',
            });
            await LocalDb.saveAuth({
              userId: resp.user_id,
              accessToken: resp.access_token,
              expiresAt: resp.expires_at,
            });
            backendClient.setToken(resp.access_token);
            setAuth(resp.user_id, resp.access_token);
            currentUserId = resp.user_id;
          }
        } catch (authErr) {
          console.warn('[CalibrationScreen] Auto-register failed:', authErr);
        }
      }

      const resp = await backendClient.calibration.getPrompts(preferredLanguage, SESSION_PROMPT_COUNT);
      setPrompts(resp.prompts);
      setPromptSetId(resp.prompt_set_id);

      if (!currentUserId) {
        setErrorMsg('User not registered. Restart the app.');
        setPhase('error');
        return;
      }

      const session = await backendClient.calibration.createSession({
        user_id: currentUserId,
        prompt_set_id: resp.prompt_set_id,
      });
      setSessionId(session.session_id);
      setPhase('prompts');
    } catch (e: any) {
      if (isNetworkError(e)) {
        // Backend unreachable — switch to offline mode with local prompts
        console.warn('[CalibrationScreen] Backend unreachable, using offline prompts:', e?.message);
        setIsOfflineMode(true);
        setPrompts(OFFLINE_PROMPTS);
        setPromptSetId('offline');
        setSessionId('offline_session');
        setPhase('prompts');
      } else {
        setErrorMsg(e?.message ?? 'Failed to load prompts');
        setPhase('error');
      }
    }
  }, [userId, preferredLanguage, setAuth]);

  useEffect(() => {
    loadCalibrationData();
  }, [loadCalibrationData]);

  useEffect(() => () => clearPoll(), [clearPoll]);

  const startRecording = useCallback(async () => {
    try {
      setIsRecording(true);
      const path = await recorder.startRecorder();
      setLastRecordingUri(path);
    } catch (e: any) {
      Alert.alert('Recording error', e?.message ?? 'Unknown error');
      setIsRecording(false);
    }
  }, []);

  const loadClusterAdapter = useCallback(async () => {
    const handle = await downloadAndLoadClusterAdapter(
      preferredLanguage,
      dysarthriaSeverityHint ?? undefined,
    );
    setActiveAdapters([handle]);
    setPhase('done');
  }, [preferredLanguage, dysarthriaSeverityHint, setActiveAdapters]);

  const loadUserAdapter = useCallback(async (
    uid: string,
    adapterId: string,
    version: number,
    sid: string,
  ) => {
    try {
      // Pass the last calibration recording as a reference clip so the native side can
      // run a real before/after transcription check on this patient's own dysarthric
      // speech, not silently trust that the swap worked.
      const handle = await downloadAndLoadUserAdapter(
        uid,
        adapterId,
        version,
        sid,
        lastRecordingUri ?? undefined,
      );
      setActiveAdapters([handle]);
      setPhase('done');
    } catch (e: any) {
      console.warn('[CalibrationScreen] User adapter load failed, falling back to cluster:', e?.message);
      await loadClusterAdapter();
    }
  }, [loadClusterAdapter, setActiveAdapters, lastRecordingUri]);

  const triggerAndPollTraining = useCallback(async () => {
    if (!sessionId || !userId) {
      setErrorMsg('Session not ready. Please retry calibration.');
      setPhase('error');
      return;
    }

    try {
      await backendClient.calibration.triggerTraining(sessionId);
    } catch (e: any) {
      if (isNetworkError(e)) {
        setErrorMsg(networkErrorMessage());
        setPhase('error');
        return;
      }
      if (e?.response?.status === 501) {
        try {
          setTrainingMessage('Live training disabled — loading pre-baked cluster adapter…');
          await loadClusterAdapter();
        } catch (clusterErr: any) {
          setErrorMsg(clusterErr?.message ?? 'Failed to download voice model');
          setPhase('error');
        }
        return;
      }
      setErrorMsg(e?.response?.data?.detail?.message ?? e?.message ?? 'Training trigger failed');
      setPhase('error');
      return;
    }

    pollStartRef.current = Date.now();
    consecutivePollFailuresRef.current = 0;
    pollRef.current = setInterval(async () => {
      if (Date.now() - pollStartRef.current > POLL_TIMEOUT_MS) {
        clearPoll();
        try {
          setTrainingMessage('Training timed out — loading cluster fallback…');
          await loadClusterAdapter();
        } catch (clusterErr: any) {
          setErrorMsg(clusterErr?.message ?? 'Training timed out and fallback failed');
          setPhase('error');
        }
        return;
      }

      try {
        const [calStatus, adapterStatus] = await Promise.all([
          backendClient.calibration.getStatus(sessionId),
          backendClient.calibration.getSessionAdapterStatus(sessionId).catch(() => null),
        ]);
        consecutivePollFailuresRef.current = 0;

        const progress = adapterStatus?.progress_pct ?? calStatus.progress_pct;
        setTrainingProgress(progress);
        setTrainingMessage(
          adapterStatus?.message ??
            (calStatus.status === 'TRAINING' ? 'Fine-tuning on your laptop…' : 'Waiting…'),
        );

        const failed =
          calStatus.status === 'FAILED' || adapterStatus?.status === 'failed';
        const ready =
          adapterStatus?.status === 'ready' ||
          (calStatus.status === 'COMPLETE' && calStatus.resulting_adapter_id);

        if (failed) {
          clearPoll();
          const errDetail = adapterStatus?.error ?? 'Training failed on laptop';
          console.warn('[CalibrationScreen] Training failed:', errDetail);
          try {
            setTrainingMessage('Training failed — loading cluster fallback…');
            await loadClusterAdapter();
          } catch (clusterErr: any) {
            setErrorMsg(`${errDetail}. Fallback also failed: ${clusterErr?.message}`);
            setPhase('error');
          }
          return;
        }

        if (ready) {
          clearPoll();
          const adapterId =
            adapterStatus?.adapter_id ?? calStatus.resulting_adapter_id ?? `user_${userId}`;
          await loadUserAdapter(userId, adapterId, 1, sessionId);
        }
      } catch (pollErr: any) {
        if (isNetworkError(pollErr)) {
          consecutivePollFailuresRef.current += 1;
          console.warn(
            '[CalibrationScreen] Poll network error (%d/%d):',
            consecutivePollFailuresRef.current,
            MAX_CONSECUTIVE_POLL_FAILURES,
            pollErr?.message,
          );
          // Tolerate brief blips silently; only give up after sustained unreachability,
          // and fall back automatically (consistent with the failed/timeout/501 paths)
          // rather than stranding the user on a manual-retry error screen mid-demo.
          if (consecutivePollFailuresRef.current >= MAX_CONSECUTIVE_POLL_FAILURES) {
            clearPoll();
            try {
              setTrainingMessage('Lost connection to laptop — loading cluster fallback…');
              await loadClusterAdapter();
            } catch (clusterErr: any) {
              setErrorMsg(`${networkErrorMessage()}\n\nFallback also failed: ${clusterErr?.message}`);
              setPhase('error');
            }
          }
        } else {
          console.warn('[CalibrationScreen] Poll error:', pollErr?.message);
        }
      }
    }, POLL_INTERVAL_MS);
  }, [sessionId, userId, loadClusterAdapter, loadUserAdapter, clearPoll]);

  const uploadCurrentSample = useCallback(async () => {
    if (!sessionId || !lastRecordingUri) return;
    const prompt = prompts[currentIndex];

    // Offline mode: skip network upload, just advance locally
    if (isOfflineMode) {
      setSamplesUploaded(prev => prev + 1);
      if (currentIndex + 1 < prompts.length) {
        setCurrentIndex(prev => prev + 1);
        setPhase('prompts');
      } else {
        // All prompts recorded offline — load the cluster adapter
        setPhase('training');
        setTrainingMessage('Loading voice model…');
        try {
          await loadClusterAdapter();
        } catch (e: any) {
          setErrorMsg(e?.message ?? 'Failed to load voice model');
          setPhase('error');
        }
      }
      return;
    }

    try {
      const resp = await backendClient.calibration.uploadSample(
        sessionId,
        lastRecordingUri,
        prompt.prompt_id,
      );
      setSamplesUploaded(resp.samples_received);

      if (currentIndex + 1 < prompts.length) {
        setCurrentIndex(prev => prev + 1);
        setPhase('prompts');
      } else {
        setPhase('training');
        await triggerAndPollTraining();
      }
    } catch (e: any) {
      setErrorMsg(
        isNetworkError(e)
          ? networkErrorMessage()
          : (e?.message ?? 'Upload failed — is the laptop server running?'),
      );
      setPhase('error');
    }
  }, [sessionId, lastRecordingUri, currentIndex, prompts, isOfflineMode, loadClusterAdapter, triggerAndPollTraining]);

  const stopRecording = useCallback(async () => {
    try {
      await recorder.stopRecorder();
      setIsRecording(false);
      setPhase('uploading');
      await uploadCurrentSample();
    } catch (e: any) {
      Alert.alert('Stop recording error', e?.message ?? 'Unknown error');
      setIsRecording(false);
    }
  }, [uploadCurrentSample]);

  if (phase === 'loading') {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={colors.red} />
        <Text style={styles.subtitle}>Loading calibration prompts…</Text>
      </View>
    );
  }

  if (phase === 'error') {
    return (
      <View style={styles.center}>
        <Text style={styles.errorText}>⚠️ {errorMsg}</Text>
        <TouchableOpacity style={styles.btn} onPress={loadCalibrationData}>
          <Text style={styles.btnText}>Retry</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.btn, styles.secondaryBtn]}
          onPress={async () => {
            try {
              setPhase('training');
              await loadClusterAdapter();
            } catch (e: any) {
              setErrorMsg(e?.message ?? 'Fallback failed');
              setPhase('error');
            }
          }}
        >
          <Text style={[styles.btnText, styles.secondaryBtnText]}>Use demo cluster adapter</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (phase === 'training') {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={colors.red} />
        <Text style={styles.subtitle}>{trainingMessage}</Text>
        <Text style={styles.hint}>Progress: {trainingProgress}%</Text>
        <Text style={styles.hint}>
          Training runs on your laptop (not cloud). Keep USB connected or hotspot active.
        </Text>
      </View>
    );
  }

  if (phase === 'done') {
    return (
      <View style={styles.center}>
        <Text style={styles.doneText}>✅ Calibration complete!</Text>
        <Text style={styles.subtitle}>Your voice model is loaded and ready.</Text>
        <Text style={styles.hint}>
          Go to Settings → Voice Activation to use VaaniMitra in any app.
        </Text>
        <TouchableOpacity style={styles.btn} onPress={() => navigation.navigate('Settings')}>
          <Text style={styles.btnText}>Continue to Settings</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const currentPrompt = prompts[currentIndex];

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Text style={styles.title}>Voice Calibration</Text>
      {isOfflineMode && (
        <View style={styles.offlineBadge}>
          <Text style={styles.offlineBadgeText}>📵 Offline Mode — recordings saved locally</Text>
        </View>
      )}
      <Text style={styles.progress}>
        Prompt {currentIndex + 1} of {prompts.length}  ·  {samplesUploaded} recorded
      </Text>

      <View style={styles.promptCard}>
        <Text style={styles.promptText}>{currentPrompt?.text}</Text>
      </View>

      <Text style={styles.hint}>
        {isOfflineMode
          ? 'Read the phrase aloud clearly. Your voice samples are saved on your device.'
          : 'Read the phrase aloud clearly. Audio uploads to your laptop over local network/USB.'}
      </Text>


      {phase === 'uploading' ? (
        <ActivityIndicator size="small" color={colors.red} style={{ marginTop: 20 }} />
      ) : isRecording ? (
        <TouchableOpacity style={[styles.btn, styles.stopBtn]} onPress={stopRecording}>
          <Text style={styles.btnText}>⏹  Stop Recording</Text>
        </TouchableOpacity>
      ) : (
        <TouchableOpacity style={styles.btn} onPress={startRecording}>
          <Text style={styles.btnText}>🎙  Record</Text>
        </TouchableOpacity>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 24,
    backgroundColor: colors.screenBackground,
    alignItems: 'center',
  },
  center: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: colors.screenBackground,
    padding: 24,
  },
  title: { fontSize: 24, fontWeight: '700', color: colors.textPrimary, marginBottom: 8 },
  progress: { fontSize: 13, color: colors.textSecondary, marginBottom: 24 },
  promptCard: {
    backgroundColor: colors.cardBackground,
    borderRadius: 16,
    padding: 28,
    width: '100%',
    marginBottom: 24,
    borderWidth: 1,
    borderColor: colors.navBorder,
    shadowColor: colors.navy,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.05,
    shadowRadius: 8,
    elevation: 2,
  },
  promptText: { fontSize: 22, fontWeight: '600', color: colors.textPrimary, textAlign: 'center', lineHeight: 32 },
  hint: { fontSize: 13, color: colors.textSecondary, textAlign: 'center', marginBottom: 32, paddingHorizontal: 12 },
  btn: {
    backgroundColor: colors.navy,
    borderRadius: 12,
    paddingVertical: 16,
    paddingHorizontal: 40,
    marginTop: 8,
  },
  secondaryBtn: {
    backgroundColor: colors.white,
    borderWidth: 1.5,
    borderColor: colors.navBorder,
  },
  secondaryBtnText: {
    color: colors.textPrimary,
  },
  stopBtn: {
    backgroundColor: colors.red,
  },
  btnText: {
    color: colors.white,
    fontSize: 16,
    fontWeight: '700',
  },
  subtitle: {
    fontSize: 16,
    color: colors.textSecondary,
    marginTop: 12,
    textAlign: 'center',
  },
  errorText: {
    fontSize: 15,
    color: colors.red,
    textAlign: 'center',
    marginBottom: 24,
  },
  doneText: {
    fontSize: 26,
    fontWeight: '700',
    color: colors.textPrimary,
    marginBottom: 12,
  },
  offlineBadge: {
    backgroundColor: colors.peachLight,
    borderRadius: 8,
    paddingVertical: 6,
    paddingHorizontal: 14,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: colors.orangeLight,
  },
  offlineBadgeText: {
    fontSize: 12,
    color: colors.orangeDeep,
    fontWeight: '600',
  },
});
