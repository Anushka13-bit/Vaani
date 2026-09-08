/**
 * VaaniMitra — CalibrationScreen (§3.1)
 * Full calibration flow:
 *   1. Fetch prompts from backend
 *   2. Record audio for each prompt
 *   3. Upload samples
 *   4. Trigger training (gracefully handles 501 stub — falls back to cluster adapter)
 *   5. Download + load adapter via SpeechBridge
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
import { SpeechBridge } from '../native/SpeechBridge';
import { useStore } from '../state/store';
import type { Prompt } from '../api/dto';

const recorder = new AudioRecorderPlayer();

export default function CalibrationScreen({ navigation }: any) {
  const { userId, preferredLanguage, dysarthriaSeverityHint, setActiveAdapters } = useStore();

  const [prompts, setPrompts] = useState<Prompt[]>([]);
  const [promptSetId, setPromptSetId] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isRecording, setIsRecording] = useState(false);
  const [lastRecordingUri, setLastRecordingUri] = useState<string | null>(null);
  const [samplesUploaded, setSamplesUploaded] = useState(0);
  const [phase, setPhase] = useState<
    'loading' | 'prompts' | 'recording' | 'uploading' | 'training' | 'done' | 'error'
  >('loading');
  const [errorMsg, setErrorMsg] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // ── 1. Load prompts ──────────────────────────────────────────────────────────

  const loadCalibrationData = useCallback(async () => {
    try {
      setPhase('loading');
      setErrorMsg('');
      const resp = await backendClient.calibration.getPrompts(preferredLanguage, 40);
      setPrompts(resp.prompts);
      setPromptSetId(resp.prompt_set_id);

      if (userId) {
        try {
          const session = await backendClient.calibration.createSession({
            user_id: userId,
            prompt_set_id: resp.prompt_set_id,
          });
          setSessionId(session.session_id);
        } catch (sessionErr: any) {
          console.warn('[CalibrationScreen] Session init:', sessionErr?.message);
        }
      }
      setPhase('prompts');
    } catch (e: any) {
      setErrorMsg(e?.message ?? 'Failed to load prompts');
      setPhase('error');
    }
  }, [userId, preferredLanguage]);

  useEffect(() => {
    loadCalibrationData();
  }, [loadCalibrationData]);

  // ── 2. Record ────────────────────────────────────────────────────────────────

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
  }, [lastRecordingUri, sessionId, currentIndex, prompts]);

  // ── 3. Upload sample ─────────────────────────────────────────────────────────

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
      setErrorMsg(e?.message ?? 'Upload failed');
      setPhase('error');
    }
  }, [sessionId, lastRecordingUri, currentIndex, prompts]);

  // ── 4. Trigger training (gracefully handles 501 stub) ────────────────────────

  const triggerAndPollTraining = useCallback(async () => {
    if (!sessionId || !userId) return;
    try {
      await backendClient.calibration.triggerTraining(sessionId);
    } catch (e: any) {
      // 501 = live training disabled — fall through to cluster adapter
      if (e?.response?.status === 501) {
        await loadClusterAdapter();
        return;
      }
      setErrorMsg(e?.message ?? 'Training trigger failed');
      setPhase('error');
      return;
    }

    // Poll for completion
    pollRef.current = setInterval(async () => {
      try {
        const status = await backendClient.calibration.getStatus(sessionId);
        if (status.status === 'COMPLETE' && status.resulting_adapter_id) {
          clearInterval(pollRef.current!);
          await loadUserAdapter(userId);
        } else if (status.status === 'FAILED') {
          clearInterval(pollRef.current!);
          await loadClusterAdapter(); // fallback
        }
      } catch (_) {}
    }, 3000);
  }, [sessionId, userId]);

  // ── 5. Load adapter ──────────────────────────────────────────────────────────

  const loadUserAdapter = async (uid: string) => {
    try {
      const handle = await SpeechBridge.loadUserAdapter(uid);
      setActiveAdapters([handle]);
      setPhase('done');
    } catch (_) {
      await loadClusterAdapter();
    }
  };

  const loadClusterAdapter = async () => {
    try {
      const cluster = await backendClient.adapters.getClusterAdapter(
        preferredLanguage,
        dysarthriaSeverityHint ?? undefined,
      );
      const handle = await SpeechBridge.loadLanguageAdapter(cluster.adapter_id);
      setActiveAdapters([handle]);
    } catch (_) {}
    setPhase('done');
  };

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  // ── Render ────────────────────────────────────────────────────────────────────

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
      </View>
    );
  }

  if (phase === 'training') {
    return (
      <View style={styles.center}>
        <ActivityIndicator size="large" color="#6C63FF" />
        <Text style={styles.subtitle}>Personalising your voice model…</Text>
        <Text style={styles.hint}>This may take a few minutes.</Text>
      </View>
    );
  }

  if (phase === 'done') {
    return (
      <View style={styles.center}>
        <Text style={styles.doneText}>✅ Calibration complete!</Text>
        <Text style={styles.subtitle}>Your personalised voice model is ready.</Text>
        <TouchableOpacity style={styles.btn} onPress={() => navigation.navigate('Settings')}>
          <Text style={styles.btnText}>Continue</Text>
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
        Read the phrase aloud clearly. Tap Record when ready.
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
  hint: { fontSize: 13, color: '#888', textAlign: 'center', marginBottom: 32 },
  btn: {
    backgroundColor: '#6C63FF', borderRadius: 12,
    paddingVertical: 16, paddingHorizontal: 40, marginTop: 8,
  },
  stopBtn: { backgroundColor: '#E74C3C' },
  btnText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  subtitle: { fontSize: 16, color: '#ccc', marginTop: 12, textAlign: 'center' },
  errorText: { fontSize: 16, color: '#E74C3C', textAlign: 'center', marginBottom: 24 },
  doneText: { fontSize: 28, color: '#6C63FF', marginBottom: 12 },
});
