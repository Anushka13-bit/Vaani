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
import { backendClient } from '../api/trainingBackendClient';
import { useStore } from '../state/store';
import type { Prompt } from '../api/dto';
import {
  downloadAndLoadClusterAdapter,
  downloadAndLoadUserAdapter,
} from '../services/adapterService';
import { SpeechBridge } from '../native/SpeechBridge';
import DeviceInfo from 'react-native-device-info';
import { LocalDb } from '../storage/localDb';

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

      const resp = await backendClient.calibration.getPrompts(preferredLanguage, 40);
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
      setErrorMsg(isNetworkError(e) ? networkErrorMessage() : (e?.message ?? 'Failed to load prompts'));
      setPhase('error');
    }
  }, [userId, preferredLanguage, setAuth]);

  useEffect(() => {
    navigation.setOptions({
      headerRight: () => (
        <TouchableOpacity
          onPress={() => navigation.navigate('Settings')}
          style={{ paddingHorizontal: 12, paddingVertical: 6 }}
        >
          <Text style={{ color: '#6C63FF', fontWeight: '700', fontSize: 14 }}>Settings</Text>
        </TouchableOpacity>
      ),
    });
  }, [navigation]);

  useEffect(() => {
    loadCalibrationData();
  }, [loadCalibrationData]);

  useEffect(() => () => clearPoll(), [clearPoll]);

  const startRecording = useCallback(async () => {
    if (!sessionId || !prompts[currentIndex]) return;
    try {
      setIsRecording(true);
      await SpeechBridge.startCalibrationRecording(
        sessionId,
        currentIndex + 1,
        prompts[currentIndex].text,
      );
    } catch (e: any) {
      Alert.alert('Recording error', e?.message ?? 'Failed to start recording');
      setIsRecording(false);
    }
  }, [sessionId, prompts, currentIndex]);

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

  const pollTrainingStatus = useCallback(async (sid: string, uid: string) => {
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
        const adapterStatus = await backendClient.calibration
          .getSessionAdapterStatus(sid)
          .catch(() => null);

        const calStatus = await backendClient.calibration
          .getStatus(sid)
          .catch(() => null);

        const progress = adapterStatus?.progress_pct ?? calStatus?.progress_pct ?? 10;
        setTrainingProgress(progress);
        setTrainingMessage(
          adapterStatus?.message ??
            (calStatus?.status === 'TRAINING' ? 'Fine-tuning on your laptop…' : 'Processing on laptop…'),
        );

        const failed =
          adapterStatus?.status === 'failed' || calStatus?.status === 'FAILED';
        const ready =
          adapterStatus?.status === 'ready' ||
          (calStatus?.status === 'COMPLETE' && calStatus?.resulting_adapter_id);

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
            adapterStatus?.adapter_id ?? calStatus?.resulting_adapter_id ?? `user_${uid}`;
          await loadUserAdapter(uid, adapterId, 1, sid);
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
  }, [loadClusterAdapter, loadUserAdapter, clearPoll]);

  const uploadBatchAndTrain = useCallback(async (sid: string, uid: string) => {
    try {
      setPhase('uploading');
      setTrainingMessage('Uploading all calibration clips and manifest in single batch…');

      const baseUrl = backendClient.calibration.getBaseUrl();
      const token = backendClient.getToken() || '';

      await SpeechBridge.uploadCalibrationBatch(sid, baseUrl, token);

      setPhase('training');
      setTrainingMessage('Local fine-tune queued on laptop…');
      await pollTrainingStatus(sid, uid);
    } catch (uploadErr: any) {
      console.warn('[CalibrationScreen] Batch upload failed:', uploadErr);
      setErrorMsg(
        isNetworkError(uploadErr)
          ? networkErrorMessage()
          : (uploadErr?.message ?? 'Batch upload failed — check laptop server'),
      );
      setPhase('error');
    }
  }, [pollTrainingStatus]);

  const stopRecording = useCallback(async () => {
    if (!sessionId || !prompts[currentIndex]) return;
    try {
      setIsRecording(false);
      const prompt = prompts[currentIndex];
      const result = await SpeechBridge.stopCalibrationRecording(
        sessionId,
        currentIndex + 1,
        prompt.text,
      );
      setLastRecordingUri(result.filePath);
      setSamplesUploaded(prev => prev + 1);

      if (currentIndex + 1 < prompts.length) {
        setCurrentIndex(prev => prev + 1);
      } else {
        // All clips recorded — send single batched request
        if (userId) {
          await uploadBatchAndTrain(sessionId, userId);
        }
      }
    } catch (e: any) {
      Alert.alert('Stop recording error', e?.message ?? 'Unknown error');
      setIsRecording(false);
    }
  }, [sessionId, prompts, currentIndex, userId, uploadBatchAndTrain]);

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
        <Text style={[styles.hint, { marginTop: 12 }]}>
          Voice activation ("Hey Jarvis"), on-device transcription, and phrasebook work completely offline. Calibration is only required if you want to train custom voice models on your laptop.
        </Text>
        <TouchableOpacity style={styles.btn} onPress={loadCalibrationData}>
          <Text style={styles.btnText}>Retry Connection</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.btn, styles.secondaryBtn]}
          onPress={() => navigation.navigate('Settings')}
        >
          <Text style={styles.btnText}>Go to Settings (Hey Jarvis)</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.btn, styles.secondaryBtn]}
          onPress={() => navigation.navigate('Phrasebook')}
        >
          <Text style={styles.btnText}>Open Phrasebook</Text>
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
