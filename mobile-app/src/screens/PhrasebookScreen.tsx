/**
 * VaaniMitra — PhrasebookScreen (§2.1, §3.3)
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, FlatList, Modal, StyleSheet, Text,
  TextInput, TouchableOpacity, View,
} from 'react-native';
import { LocalDb } from '../storage/localDb';
import { useStore } from '../state/store';
import type { ActionType, PhrasebookEntry } from '../native/types';
import { SpeechBridge } from '../native/SpeechBridge';

function inferActionFromTrigger(trigger: string, actionText: string): {
  actionType: ActionType;
  payload: Record<string, string>;
} {
  const trimmed = trigger.trim();
  const lower = trimmed.toLowerCase();

  const callMatch = lower.match(/^call\s+(.+)$/i);
  if (callMatch) {
    return {
      actionType: 'PLACE_CALL',
      payload: { contact: callMatch[1].trim() },
    };
  }

  const smsMatch = lower.match(/^text\s+(.+)$/i) || lower.match(/^message\s+(.+)$/i);
  if (smsMatch) {
    return {
      actionType: 'SEND_MESSAGE',
      payload: { contact: smsMatch[1].trim(), body: actionText || '' },
    };
  }

  if (lower.startsWith('search ')) {
    return {
      actionType: 'WEB_SEARCH',
      payload: { query: trimmed.substring(7).trim() },
    };
  }

  if (lower.startsWith('open ')) {
    return {
      actionType: 'OPEN_APP',
      payload: { app: trimmed.substring(5).trim() },
    };
  }

  return {
    actionType: 'DICTATE_TEXT',
    payload: { text: actionText || trimmed },
  };
}

function buildEntry(
  userId: string,
  trigger: string,
  actionText: string,
  existing?: PhrasebookEntry,
): PhrasebookEntry {
  const { actionType, payload } = inferActionFromTrigger(trigger, actionText);
  return {
    id: existing?.id ?? `phrase_${Date.now()}`,
    userId,
    triggerPhrase: trigger.trim(),
    actionType,
    actionPayloadJson: JSON.stringify(payload),
    createdAt: existing?.createdAt ?? Date.now(),
    lastUsedAt: existing?.lastUsedAt ?? null,
    useCount: existing?.useCount ?? 0,
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
      await SpeechBridge.syncPhrasebookBulk(JSON.stringify(loaded));
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
    try {
      const payload = JSON.parse(entry.actionPayloadJson);
      setAction(payload.text ?? payload.body ?? '');
    } catch {
      setAction('');
    }
    setModalVisible(true);
  };

  const save = useCallback(async () => {
    if (!trigger.trim()) return;
    const uid = userId ?? 'local';
    const entry = buildEntry(uid, trigger, action, editingEntry ?? undefined);

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

    try {
      await SpeechBridge.syncPhrasebookEntry(JSON.stringify(entry));
    } catch (_) {}

    setModalVisible(false);
  }, [trigger, action, editingEntry, userId, addEntry, updateEntry]);

  const confirmDelete = (entry: PhrasebookEntry) => {
    Alert.alert('Delete phrase', `Remove "${entry.triggerPhrase}"?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete', style: 'destructive', onPress: async () => {
          removeEntry(entry.id);
          await LocalDb.deletePhrasebookEntry(entry.id);
          try {
            await SpeechBridge.removePhrasebookEntry(entry.triggerPhrase);
          } catch (_) {}
        },
      },
    ]);
  };

  const actionLabel = (entry: PhrasebookEntry): string => {
    try {
      const payload = JSON.parse(entry.actionPayloadJson);
      if (entry.actionType === 'PLACE_CALL') return `Call ${payload.contact}`;
      if (entry.actionType === 'SEND_MESSAGE') return `Message ${payload.contact}`;
      if (entry.actionType === 'WEB_SEARCH') return `Search: ${payload.query}`;
      if (entry.actionType === 'OPEN_APP') return `Open ${payload.app}`;
      return payload.text ?? entry.actionType;
    } catch {
      return entry.actionType;
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Phrasebook</Text>
      <Text style={styles.subtitle}>
        Shortcuts like &quot;call Ravi&quot; work when you use the system mic in any app.
      </Text>

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
              <Text style={styles.phraseText}>&quot;{item.triggerPhrase}&quot;</Text>
              <Text style={styles.actionText}>{actionLabel(item)}</Text>
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
            <Text style={styles.label}>Optional note / message body</Text>
            <TextInput
              style={styles.input}
              value={action}
              onChangeText={setAction}
              placeholder="e.g. Running late"
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
  subtitle: { fontSize: 13, color: '#888', marginBottom: 20, lineHeight: 18 },
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
