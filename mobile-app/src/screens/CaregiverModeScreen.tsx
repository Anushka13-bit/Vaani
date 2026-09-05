/**
 * VaaniMitra — CaregiverModeScreen
 * PIN-gated secondary view showing low-confidence transcripts and phrasebook management.
 */
import React, { useState } from 'react';
import {
  Alert, FlatList, StyleSheet, Text, TextInput,
  TouchableOpacity, View,
} from 'react-native';
import { backendClient } from '../api/trainingBackendClient';
import { useStore } from '../state/store';
import type { TranscriptEntry } from '../api/dto';

const CAREGIVER_PIN = '1234'; // TODO: replace with secure storage

export default function CaregiverModeScreen() {
  const { userId } = useStore();
  const [pinInput, setPinInput] = useState('');
  const [unlocked, setUnlocked] = useState(false);
  const [transcripts, setTranscripts] = useState<TranscriptEntry[]>([]);
  const [loading, setLoading] = useState(false);

  const unlock = () => {
    if (pinInput === CAREGIVER_PIN) {
      setUnlocked(true);
      loadTranscripts();
    } else {
      Alert.alert('Wrong PIN', 'Please try again.');
      setPinInput('');
    }
  };

  const loadTranscripts = async () => {
    if (!userId) return;
    setLoading(true);
    try {
      const resp = await backendClient.caregiver.getTranscripts(userId, 0.6, 50);
      setTranscripts(resp.transcripts);
    } catch (e: any) {
      Alert.alert('Error', e?.message ?? 'Failed to load transcripts');
    } finally {
      setLoading(false);
    }
  };

  if (!unlocked) {
    return (
      <View style={styles.center}>
        <Text style={styles.title}>Caregiver Mode</Text>
        <Text style={styles.subtitle}>Enter PIN to access caregiver view</Text>
        <TextInput
          style={styles.pinInput}
          value={pinInput}
          onChangeText={setPinInput}
          keyboardType="numeric"
          secureTextEntry
          maxLength={6}
          placeholder="PIN"
          placeholderTextColor="#555"
        />
        <TouchableOpacity style={styles.btn} onPress={unlock}>
          <Text style={styles.btnText}>Unlock</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Low-Confidence Transcripts</Text>
      <Text style={styles.subtitle}>Review and help correct uncertain transcriptions</Text>

      {loading ? (
        <Text style={styles.dimText}>Loading…</Text>
      ) : (
        <FlatList
          data={transcripts}
          keyExtractor={item => item.id}
          ListEmptyComponent={<Text style={styles.empty}>No low-confidence transcripts.</Text>}
          renderItem={({ item }) => (
            <View style={styles.card}>
              <Text style={styles.confidence}>
                {(item.confidence * 100).toFixed(0)}% confidence  ·  {new Date(item.timestamp).toLocaleString()}
              </Text>
              <Text style={styles.transcriptText}>{item.transcript}</Text>
            </View>
          )}
        />
      )}

      <TouchableOpacity style={[styles.btn, { backgroundColor: '#333', marginTop: 16 }]} onPress={() => setUnlocked(false)}>
        <Text style={[styles.btnText, { color: '#E74C3C' }]}>Lock Caregiver Mode</Text>
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0F0F1A', padding: 20 },
  center: { flex: 1, backgroundColor: '#0F0F1A', justifyContent: 'center', alignItems: 'center', padding: 32 },
  title: { fontSize: 22, fontWeight: '700', color: '#E8E8FF', marginBottom: 8 },
  subtitle: { fontSize: 13, color: '#888', marginBottom: 24, textAlign: 'center' },
  pinInput: {
    backgroundColor: '#1A1A2E', color: '#E8E8FF', borderRadius: 12,
    padding: 16, fontSize: 24, letterSpacing: 8, textAlign: 'center',
    width: 160, marginBottom: 20, borderWidth: 1, borderColor: '#333',
  },
  btn: { backgroundColor: '#6C63FF', borderRadius: 12, paddingVertical: 14, paddingHorizontal: 40 },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
  card: { backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16, marginBottom: 10 },
  confidence: { color: '#6C63FF', fontSize: 11, marginBottom: 6 },
  transcriptText: { color: '#E8E8FF', fontSize: 15 },
  dimText: { color: '#555', fontSize: 14 },
  empty: { color: '#555', textAlign: 'center', marginTop: 60, fontSize: 15 },
});
