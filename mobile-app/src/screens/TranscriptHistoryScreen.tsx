/**
 * VaaniMitra — TranscriptHistoryScreen
 * Displays local + low-confidence transcripts; allows correction submission.
 */
import React, { useEffect, useState } from 'react';
import {
  Alert, FlatList, StyleSheet, Text, TextInput,
  TouchableOpacity, View,
} from 'react-native';
import { LocalDb } from '../storage/localDb';
import { backendClient } from '../api/trainingBackendClient';
import { useStore } from '../state/store';
import type { CorrectionRecord } from '../native/types';

export default function TranscriptHistoryScreen() {
  const { userId, correctionSyncOptIn } = useStore();
  const [corrections, setCorrections] = useState<CorrectionRecord[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editText, setEditText] = useState('');

  useEffect(() => {
    LocalDb.loadCorrections().then(setCorrections);
  }, []);

  const submitCorrection = async (record: CorrectionRecord, corrected: string) => {
    const updated: CorrectionRecord = {
      ...record,
      correctedTranscript: corrected,
    };
    await LocalDb.appendCorrection(updated);
    setCorrections(prev => [...prev.filter(r => r.id !== record.id), updated]);
    setEditingId(null);

    if (correctionSyncOptIn && userId) {
      try {
        await backendClient.corrections.upload(userId, [{
          original_transcript: record.originalTranscript,
          corrected_transcript: corrected,
          confidence_at_time: record.confidenceAtTime,
          audio_included: false,
        }]);
        await LocalDb.markCorrectionsSynced([record.id]);
      } catch (e: any) {
        Alert.alert('Sync failed', e?.message ?? 'Could not sync correction');
      }
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Transcript History</Text>
      <Text style={styles.subtitle}>Tap a transcript to correct it</Text>

      <FlatList
        data={corrections}
        keyExtractor={item => item.id}
        ListEmptyComponent={<Text style={styles.empty}>No transcripts yet.</Text>}
        renderItem={({ item }) => (
          <View style={styles.card}>
            <Text style={styles.confidence}>
              Confidence: {(item.confidenceAtTime * 100).toFixed(0)}%
              {item.syncedToBackend ? '  ✓ synced' : ''}
            </Text>
            <Text style={styles.transcript}>{item.originalTranscript}</Text>

            {editingId === item.id ? (
              <>
                <TextInput
                  style={styles.input}
                  value={editText}
                  onChangeText={setEditText}
                  autoFocus
                  placeholder="Corrected transcript"
                  placeholderTextColor="#555"
                />
                <View style={styles.editActions}>
                  <TouchableOpacity onPress={() => setEditingId(null)} style={styles.cancelBtn}>
                    <Text style={styles.cancelText}>Cancel</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    onPress={() => submitCorrection(item, editText)}
                    style={styles.saveBtn}
                  >
                    <Text style={styles.saveText}>Submit</Text>
                  </TouchableOpacity>
                </View>
              </>
            ) : (
              <TouchableOpacity
                onPress={() => { setEditingId(item.id); setEditText(item.originalTranscript); }}
                style={styles.editBtn}
              >
                <Text style={styles.editBtnText}>✏️ Correct</Text>
              </TouchableOpacity>
            )}
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0F0F1A', padding: 20 },
  title: { fontSize: 24, fontWeight: '700', color: '#E8E8FF', marginBottom: 4 },
  subtitle: { fontSize: 13, color: '#888', marginBottom: 20 },
  empty: { color: '#555', textAlign: 'center', marginTop: 60, fontSize: 15 },
  card: { backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16, marginBottom: 10 },
  confidence: { color: '#6C63FF', fontSize: 11, marginBottom: 6 },
  transcript: { color: '#E8E8FF', fontSize: 15 },
  input: {
    backgroundColor: '#0F0F1A', color: '#E8E8FF', borderRadius: 8,
    padding: 10, fontSize: 14, borderWidth: 1, borderColor: '#444', marginTop: 8,
  },
  editActions: { flexDirection: 'row', gap: 8, marginTop: 8 },
  cancelBtn: { flex: 1, padding: 10, borderRadius: 8, borderWidth: 1, borderColor: '#333', alignItems: 'center' },
  cancelText: { color: '#888', fontSize: 13 },
  saveBtn: { flex: 1, padding: 10, borderRadius: 8, backgroundColor: '#6C63FF', alignItems: 'center' },
  saveText: { color: '#fff', fontSize: 13, fontWeight: '700' },
  editBtn: { marginTop: 8 },
  editBtnText: { color: '#6C63FF', fontSize: 13 },
});
