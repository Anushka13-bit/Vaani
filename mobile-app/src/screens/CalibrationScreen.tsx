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

const recorder = new AudioRecorderPlayer();
const POLL_INTERVAL_MS = 3000;
const POLL_TIMEOUT_MS = 30 * 60 * 1000; // 30 min max for local fine-tune

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
  const { userId, preferredLanguage, dysarthriaSeverityHint, setActiveAdapters } = useStore();

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
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pollStartRef = useRef<number>(0);

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
      const resp = await backendClient.calibration.getPrompts(preferredLanguage, 40);
      setPrompts(resp.prompts);
      setPromptSetId(resp.prompt_set_id);

      if (!userId) {
        setErrorMsg('User not registered. Restart the app.');
        setPhase('error');
        return;
      }

      const session = await backendClient.calibration.createSession({
        user_id: userId,
        prompt_set_id: resp.prompt_set_id,
      });
      setSessionId(session.session_id);
      setPhase('prompts');
    } catch (e: any) {
      setErrorMsg(isNetworkError(e) ? networkErrorMessage() : (e?.message ?? 'Failed to load prompts'));
      setPhase('error');
    }
  }, [userId, preferredLanguage]);

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
      const handle = await downloadAndLoadUserAdapter(uid, adapterId, version, sid);
      setActiveAdapters([handle]);
      setPhase('done');
    } catch (e: any) {
      console.warn('[CalibrationScreen] User adapter load failed, falling back to cluster:', e?.message);
      await loadClusterAdapter();
    }
  }, [loadClusterAdapter, setActiveAdapters]);

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
          clearPoll();
          setErrorMsg(networkErrorMessage());
          setPhase('error');
        } else {
          console.warn('[CalibrationScreen] Poll error:', pollErr?.message);
        }
      }
    }, POLL_INTERVAL_MS);
  }, [sessionId, userId, loadClusterAdapter, loadUserAdapter, clearPoll]);

  const uploadCurrentSample = useCallback(async () => {
    if (!sessionId || !lastRecordingUri) return;
    const prompt = prompts[currentIndex];
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
  }, [sessionId, lastRecordingUri, currentIndex, prompts, triggerAndPollTraining]);

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
        <ActivityIndicator size="large" color="#6C63FF" />
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
          <Text style={styles.btnText}>Use demo cluster adapter</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (phase === 'training') {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#6C63FF" />
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
      <Text style={styles.progress}>
        Prompt {currentIndex + 1} of {prompts.length}  ·  {samplesUploaded} uploaded
      </Text>

      <View style={styles.promptCard}>
        <Text style={styles.promptText}>{currentPrompt?.text}</Text>
      </View>

      <Text style={styles.hint}>
        Read the phrase aloud clearly. Audio uploads to your laptop over local network/USB.
      </Text>

      {phase === 'uploading' ? (
        <ActivityIndicator size="small" color="#6C63FF" style={{ marginTop: 20 }} />
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
  container: { flex: 1, padding: 24, backgroundColor: '#0F0F1A', alignItems: 'center' },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: '#0F0F1A', padding: 24 },
  title: { fontSize: 24, fontWeight: '700', color: '#E8E8FF', marginBottom: 8 },
  progress: { fontSize: 13, color: '#888', marginBottom: 24 },
  promptCard: {
    backgroundColor: '#1A1A2E', borderRadius: 16, padding: 28,
    width: '100%', marginBottom: 24,
    shadowColor: '#6C63FF', shadowOpacity: 0.3, shadowRadius: 12,
  },
  promptText: { fontSize: 22, color: '#E8E8FF', textAlign: 'center', lineHeight: 32 },
  hint: { fontSize: 13, color: '#888', textAlign: 'center', marginBottom: 32, paddingHorizontal: 12 },
  btn: {
    backgroundColor: '#6C63FF', borderRadius: 12,
    paddingVertical: 16, paddingHorizontal: 40, marginTop: 8,
  },
  secondaryBtn: { backgroundColor: '#444' },
  stopBtn: { backgroundColor: '#E74C3C' },
  btnText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  subtitle: { fontSize: 16, color: '#ccc', marginTop: 12, textAlign: 'center' },
  errorText: { fontSize: 16, color: '#E74C3C', textAlign: 'center', marginBottom: 24 },
  doneText: { fontSize: 28, color: '#6C63FF', marginBottom: 12 },
});
