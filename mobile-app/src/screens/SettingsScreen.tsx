/**
 * VaaniMitra — SettingsScreen
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, ScrollView, StyleSheet, Switch, Text, TouchableOpacity, View,
} from 'react-native';
import { useStore } from '../state/store';
import { AccessibilityBridge } from '../native/AccessibilityBridge';
import { SpeechBridge } from '../native/SpeechBridge';
import { LocalDb } from '../storage/localDb';
import { downloadAndLoadClusterAdapter } from '../services/adapterService';

export default function SettingsScreen({ navigation }: any) {
  const {
    activeAdapters,
    preferredLanguage,
    dysarthriaSeverityHint,
    correctionSyncOptIn,
    setActiveAdapters,
    setCorrectionSyncOptIn,
    clearAuth,
  } = useStore();

  const [accessibilityEnabled, setAccessibilityEnabled] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const refreshAdapters = useCallback(async () => {
    setRefreshing(true);
    try {
      const handles = await SpeechBridge.getCurrentAdapterInfo();
      if (handles.length > 0) {
        setActiveAdapters(handles);
        await LocalDb.saveActiveAdapters(handles);
      }
    } catch (e) {
      console.warn('[Settings] Adapter refresh failed:', e);
    } finally {
      setRefreshing(false);
    }
  }, [setActiveAdapters]);

  useEffect(() => {
    AccessibilityBridge.isAccessibilityServiceEnabled()
      .then(setAccessibilityEnabled)
      .catch(() => {});
    refreshAdapters();
  }, [refreshAdapters]);

  const reloadAdapter = async () => {
    try {
      setRefreshing(true);
      const handle = await downloadAndLoadClusterAdapter(
        preferredLanguage,
        dysarthriaSeverityHint ?? undefined,
      );
      setActiveAdapters([handle]);
      Alert.alert('Success', 'Voice model downloaded and loaded.');
    } catch (e: any) {
      Alert.alert('Download failed', e?.message ?? 'Could not load adapter');
    } finally {
      setRefreshing(false);
    }
  };

  const toggleCorrectionSync = async (value: boolean) => {
    if (value) {
      Alert.alert(
        'Enable correction sync?',
        'Your corrected transcripts will be sent to the server to improve your voice model. ' +
        'No raw audio is sent. You can disable this at any time.',
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Enable', onPress: async () => {
              setCorrectionSyncOptIn(true);
              await LocalDb.saveSettings({ correctionSyncOptIn: true });
            },
          },
        ],
      );
    } else {
      setCorrectionSyncOptIn(false);
      await LocalDb.saveSettings({ correctionSyncOptIn: false });
    }
  };

  const signOut = async () => {
    await LocalDb.clearAuth();
    await LocalDb.clearActiveAdapters();
    clearAuth();
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Settings</Text>

      <SectionHeader title="Active Voice Model" />
      {activeAdapters.length === 0 ? (
        <View style={styles.card}>
          <Text style={styles.dimText}>No adapter loaded.</Text>
          <TouchableOpacity style={styles.btn} onPress={reloadAdapter} disabled={refreshing}>
            <Text style={styles.btnText}>{refreshing ? 'Loading…' : 'Download Voice Model'}</Text>
          </TouchableOpacity>
        </View>
      ) : (
        activeAdapters.map(a => (
          <View key={a.adapterId} style={styles.card}>
            <Text style={styles.cardTitle}>{a.adapterId}</Text>
            <Text style={styles.cardSub}>Type: {a.type}  ·  v{a.version}</Text>
            <Text style={styles.cardSub} numberOfLines={1}>
              Checksum: {a.checksum.slice(0, 24)}…
            </Text>
          </View>
        ))
      )}

      <SectionHeader title="Voice Activation" />
      <View style={styles.card}>
        <Text style={styles.cardSub}>
          VaaniMitra works <Text style={styles.bold}>outside the app</Text> once configured.
          It is <Text style={styles.bold}>not</Text> a &quot;Hey Siri&quot; always-listening assistant.
        </Text>
        <Text style={[styles.cardSub, { marginTop: 10 }]}>
          1. Set VaaniMitra as your default voice input (mic button in any app).
        </Text>
        <Text style={styles.cardSub}>
          2. Tap the mic in Messages, Chrome, etc. and speak commands like &quot;call Ravi&quot;.
        </Text>
        <Text style={styles.cardSub}>
          3. Optional: add shortcuts in Phrasebook for custom phrases.
        </Text>
        <TouchableOpacity
          style={styles.btn}
          onPress={() => SpeechBridge.openVoiceInputSettings()}
        >
          <Text style={styles.btnText}>Open Keyboard / Voice Input Settings</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.btn, styles.secondaryBtn]}
          onPress={() => navigation.navigate('Phrasebook')}
        >
          <Text style={styles.btnText}>Open Phrasebook</Text>
        </TouchableOpacity>
      </View>

      <SectionHeader title="Language" />
      <View style={styles.card}>
        <Text style={styles.cardTitle}>{preferredLanguage.toUpperCase()}</Text>
        <Text style={styles.cardSub}>Preferred transcription language</Text>
      </View>

      <SectionHeader title="Privacy" />
      <View style={styles.row}>
        <View style={{ flex: 1 }}>
          <Text style={styles.rowLabel}>Correction sync (opt-in)</Text>
          <Text style={styles.rowSub}>Send corrected transcripts to improve your model</Text>
        </View>
        <Switch
          value={correctionSyncOptIn}
          onValueChange={toggleCorrectionSync}
          trackColor={{ true: '#6C63FF' }}
        />
      </View>

      <SectionHeader title="Accessibility Service" />
      <View style={styles.card}>
        <Text style={styles.cardSub}>
          Status: {accessibilityEnabled ? '✅ Enabled' : '⚠️ Disabled'}
        </Text>
        <Text style={styles.cardSub}>
          Helps dictate text into fields when standard intents are not enough.
        </Text>
        {!accessibilityEnabled && (
          <TouchableOpacity
            style={styles.btn}
            onPress={() => AccessibilityBridge.openAccessibilitySettings()}
          >
            <Text style={styles.btnText}>Open Accessibility Settings</Text>
          </TouchableOpacity>
        )}
      </View>

      <SectionHeader title="Account" />
      <TouchableOpacity style={[styles.btn, { backgroundColor: '#333' }]} onPress={signOut}>
        <Text style={[styles.btnText, { color: '#E74C3C' }]}>Sign out</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}

function SectionHeader({ title }: { title: string }) {
  return <Text style={styles.sectionHeader}>{title}</Text>;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0F0F1A' },
  content: { padding: 20, paddingBottom: 60 },
  title: { fontSize: 24, fontWeight: '700', color: '#E8E8FF', marginBottom: 24 },
  sectionHeader: { fontSize: 11, color: '#6C63FF', fontWeight: '700', letterSpacing: 1.5, marginTop: 28, marginBottom: 10, textTransform: 'uppercase' },
  card: { backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16, marginBottom: 8 },
  cardTitle: { color: '#E8E8FF', fontSize: 15, fontWeight: '600' },
  cardSub: { color: '#888', fontSize: 12, marginTop: 2, lineHeight: 18 },
  bold: { color: '#ccc', fontWeight: '600' },
  row: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16, marginBottom: 8 },
  rowLabel: { color: '#E8E8FF', fontSize: 14, fontWeight: '500' },
  rowSub: { color: '#888', fontSize: 12, marginTop: 2 },
  dimText: { color: '#555', fontSize: 14, marginBottom: 12 },
  btn: { backgroundColor: '#6C63FF', borderRadius: 10, padding: 14, alignItems: 'center', marginTop: 8 },
  secondaryBtn: { backgroundColor: '#3A3A5E' },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 14 },
});
