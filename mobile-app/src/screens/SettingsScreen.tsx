/**
 * VaaniMitra — SettingsScreen
 * Adapter info, consent toggles, caregiver link, accessibility service setup.
 */
import React, { useEffect, useState } from 'react';
import {
  Alert, ScrollView, StyleSheet, Switch, Text, TouchableOpacity, View,
} from 'react-native';
import { useStore } from '../state/store';
import { AccessibilityBridge } from '../native/AccessibilityBridge';
import { LocalDb } from '../storage/localDb';

export default function SettingsScreen() {
  const {
    activeAdapters,
    preferredLanguage,
    correctionSyncOptIn,
    setCorrectionSyncOptIn,
    clearAuth,
  } = useStore();

  const [accessibilityEnabled, setAccessibilityEnabled] = useState(false);

  useEffect(() => {
    AccessibilityBridge.isAccessibilityServiceEnabled()
      .then(setAccessibilityEnabled)
      .catch(() => {});
  }, []);

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
    clearAuth();
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.title}>Settings</Text>

      {/* Active Adapters */}
      <SectionHeader title="Active Voice Model" />
      {activeAdapters.length === 0 ? (
        <Text style={styles.dimText}>No adapter loaded. Complete calibration first.</Text>
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

      {/* Language */}
      <SectionHeader title="Language" />
      <View style={styles.card}>
        <Text style={styles.cardTitle}>{preferredLanguage.toUpperCase()}</Text>
        <Text style={styles.cardSub}>Preferred transcription language</Text>
      </View>

      {/* Privacy */}
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

      {/* Accessibility */}
      <SectionHeader title="Accessibility Service" />
      <View style={styles.card}>
        <Text style={styles.cardSub}>
          Status: {accessibilityEnabled ? '✅ Enabled' : '⚠️ Disabled'}
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

      {/* Sign out */}
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
  cardSub: { color: '#888', fontSize: 12, marginTop: 2 },
  row: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#1A1A2E', borderRadius: 12, padding: 16, marginBottom: 8 },
  rowLabel: { color: '#E8E8FF', fontSize: 14, fontWeight: '500' },
  rowSub: { color: '#888', fontSize: 12, marginTop: 2 },
  dimText: { color: '#555', fontSize: 14 },
  btn: { backgroundColor: '#6C63FF', borderRadius: 10, padding: 14, alignItems: 'center', marginTop: 8 },
  btnText: { color: '#fff', fontWeight: '700', fontSize: 14 },
});
