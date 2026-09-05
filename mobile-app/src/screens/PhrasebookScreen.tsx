/**
 * VaaniMitra — PhrasebookScreen (§2.1, §3.3)
 * CRUD UI for user shortcut phrases — wired to localDb + native phrasebook mirror.
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, FlatList, Modal, StyleSheet, Text,
  TextInput, TouchableOpacity, View,
} from 'react-native';
import { LocalDb } from '../storage/localDb';
import { useStore } from '../state/store';
import type { PhrasebookEntry } from '../native/types';
import { NativeModules } from 'react-native';

const { SpeechModule } = NativeModules;

function newEntry(userId: string, trigger: string, action: string): PhrasebookEntry {
  return {
    id: `phrase_${Date.now()}`,
    userId,
    triggerPhrase: trigger,
    actionType: 'DICTATE_TEXT',
    actionPayloadJson: JSON.stringify({ text: action }),
    createdAt: Date.now(),
    lastUsedAt: null,
    useCount: 0,
  };
}

export default function PhrasebookScreen() {
  const { entries, setEntries, addEntry, updateEntry, removeEntry, userId } = useStore();
  const [modalVisible, setModalVisible] = useState(false);
  const [editingEntry, setEditingEntry] = useState<PhrasebookEntry | null>(null);
  const [trigger, setTrigger] = useState('');
  const [action, setAction] = useState('');

  useEffect(() => {
    (async () => {
      const loaded = await LocalDb.loadPhrasebook();
      setEntries(loaded);
    })();
  }, []);

  const openAdd = () => {
    setEditingEntry(null);
    setTrigger('');
    setAction('');
    setModalVisible(true);
  };

  const openEdit = (entry: PhrasebookEntry) => {
    setEditingEntry(entry);
    setTrigger(entry.triggerPhrase);
    try { setAction(JSON.parse(entry.actionPayloadJson)?.text ?? ''); } catch { setAction(''); }
    setModalVisible(true);
  };

  const save = useCallback(async () => {
    if (!trigger.trim()) return;
    const uid = userId ?? 'local';
    const entry = editingEntry
      ? { ...editingEntry, triggerPhrase: trigger, actionPayloadJson: JSON.stringify({ text: action }) }
      : newEntry(uid, trigger, action);

    if (editingEntry) {
      updateEntry(entry);
    } else {
      addEntry(entry);
    }

    const allEntries = await LocalDb.loadPhrasebook();
    const updated = editingEntry
      ? allEntries.map(e => e.id === entry.id ? entry : e)
      : [...allEntries, entry];
    await LocalDb.savePhrasebook(updated);

    // Push to native phrasebook mirror for fast matching without bridge round-trips
    try {
      if (SpeechModule?.syncPhrasebookEntry) {
        await SpeechModule.syncPhrasebookEntry(JSON.stringify(entry));
      }
    } catch (_) { /* native mirror is best-effort */ }

    setModalVisible(false);
  }, [trigger, action, editingEntry, userId]);

  const confirmDelete = (entry: PhrasebookEntry) => {
    Alert.alert('Delete phrase', `Remove "${entry.triggerPhrase}"?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete', style: 'destructive', onPress: async () => {
          removeEntry(entry.id);
          await LocalDb.deletePhrasebookEntry(entry.id);
        },
      },
    ]);
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phrasebook</Text>
      <Text style={styles.subtitle}>Add shortcut phrases for quick actions</Text>

      <FlatList
        data={entries}
        keyExtractor={item => item.id}
        contentContainerStyle={styles.list}
        ListEmptyComponent={
          <Text style={styles.empty}>No phrases yet. Tap + to add one.</Text>
        }
        renderItem={({ item }) => (
          <View style={styles.card}>
            <View style={{ flex: 1 }}>
              <Text style={styles.phraseText}>"{item.triggerPhrase}"</Text>
              <Text style={styles.actionText}>{item.actionType}</Text>
            </View>
            <TouchableOpacity onPress={() => openEdit(item)} style={styles.editBtn}>
              <Text style={styles.editBtnText}>Edit</Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => confirmDelete(item)} style={styles.deleteBtn}>
              <Text style={styles.deleteBtnText}>✕</Text>
            </TouchableOpacity>
          </View>
        )}
      />

      <TouchableOpacity style={styles.fab} onPress={openAdd}>
        <Text style={styles.fabText}>＋</Text>
      </TouchableOpacity>

      <Modal visible={modalVisible} transparent animationType="slide">
        <View style={styles.modalOverlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>{editingEntry ? 'Edit Phrase' : 'New Phrase'}</Text>
            <Text style={styles.label}>Trigger phrase (what you say)</Text>
            <TextInput
              style={styles.input}
              value={trigger}
              onChangeText={setTrigger}
              placeholder="e.g. call Ravi"
              placeholderTextColor="#555"
            />
            <Text style={styles.label}>Action / response text</Text>
            <TextInput
              style={styles.input}
              value={action}
              onChangeText={setAction}
              placeholder="e.g. Calling Ravi now…"
              placeholderTextColor="#555"
            />
            <View style={styles.modalActions}>
              <TouchableOpacity onPress={() => setModalVisible(false)} style={styles.cancelBtn}>
                <Text style={styles.cancelBtnText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity onPress={save} style={styles.saveBtn}>
                <Text style={styles.saveBtnText}>Save</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0F0F1A', padding: 20 },
  title: { fontSize: 24, fontWeight: '700', color: '#E8E8FF', marginBottom: 4 },
  subtitle: { fontSize: 13, color: '#888', marginBottom: 20 },
  list: { paddingBottom: 100 },
  empty: { color: '#555', textAlign: 'center', marginTop: 60, fontSize: 15 },
  card: {
    flexDirection: 'row', alignItems: 'center',
    backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16, marginBottom: 10,
  },
  phraseText: { color: '#E8E8FF', fontSize: 15, fontWeight: '600' },
  actionText: { color: '#6C63FF', fontSize: 12, marginTop: 2 },
  editBtn: { marginLeft: 8, padding: 8 },
  editBtnText: { color: '#6C63FF', fontSize: 13 },
  deleteBtn: { marginLeft: 4, padding: 8 },
  deleteBtnText: { color: '#E74C3C', fontSize: 16 },
  fab: {
    position: 'absolute', bottom: 32, right: 24,
    backgroundColor: '#6C63FF', width: 56, height: 56,
    borderRadius: 28, justifyContent: 'center', alignItems: 'center',
    shadowColor: '#6C63FF', shadowOpacity: 0.5, shadowRadius: 12, elevation: 8,
  },
  fabText: { color: '#fff', fontSize: 28, lineHeight: 32 },
  modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.7)', justifyContent: 'flex-end' },
  modalCard: { backgroundColor: '#1A1A2E', borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: 28 },
  modalTitle: { fontSize: 20, fontWeight: '700', color: '#E8E8FF', marginBottom: 20 },
  label: { fontSize: 12, color: '#888', marginBottom: 6, marginTop: 12 },
  input: {
    backgroundColor: '#0F0F1A', color: '#E8E8FF', borderRadius: 10,
    padding: 14, fontSize: 15, borderWidth: 1, borderColor: '#333',
  },
  modalActions: { flexDirection: 'row', marginTop: 24, gap: 12 },
  cancelBtn: { flex: 1, padding: 14, borderRadius: 10, borderWidth: 1, borderColor: '#333', alignItems: 'center' },
  cancelBtnText: { color: '#888', fontSize: 15 },
  saveBtn: { flex: 1, padding: 14, borderRadius: 10, backgroundColor: '#6C63FF', alignItems: 'center' },
  saveBtnText: { color: '#fff', fontSize: 15, fontWeight: '700' },
});
