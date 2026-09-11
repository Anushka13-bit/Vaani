/**
 * VaaniMitra — SettingsScreen
 * Warm card-based design system + full functional controls (wake word, adapters,
 * accessibility service, correction sync, sign out).
 */
import React, {useCallback, useEffect, useState} from 'react';
import {
  Alert,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Icon from 'react-native-vector-icons/MaterialIcons';
import {useStore} from '../state/store';
import {AccessibilityBridge} from '../native/AccessibilityBridge';
import {SpeechBridge} from '../native/SpeechBridge';
import {LocalDb} from '../storage/localDb';
import {downloadAndLoadClusterAdapter} from '../services/adapterService';
import {requestVoicePermissions} from '../App';
import SettingsItem from '../components/SettingsItem';
import BottomNavigation from '../components/BottomNavigation';
import {colors} from '../theme/colors';

export default function SettingsScreen({navigation}: any) {
  const {
    activeAdapters,
    preferredLanguage,
    dysarthriaSeverityHint,
    correctionSyncOptIn,
    wakeWordEnabled,
    wakeWordListening,
    lastWakeWordEvent,
    wakeWordStopReason,
    setActiveAdapters,
    setCorrectionSyncOptIn,
    setWakeWordEnabled,
    setWakeWordListening,
    setWakeWordStopReason,
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
    SpeechBridge.isWakeWordServiceRunning()
      .then(running => setWakeWordListening(running))
      .catch(() => {});
    SpeechBridge.getWakeWordStopReason()
      .then(reason => {
        if (reason) setWakeWordStopReason(reason);
      })
      .catch(() => {});
  }, [refreshAdapters, setWakeWordListening, setWakeWordStopReason]);

  const toggleWakeWord = async (enable: boolean) => {
    if (enable) {
      let hasPermissions = await SpeechBridge.checkVoicePermissions();
      if (!hasPermissions) {
        hasPermissions = await requestVoicePermissions();
      }
      if (!hasPermissions) {
        Alert.alert(
          'Permissions Required',
          'Microphone and Notification permissions are required to activate "Hey Lily" wake word listening.',
        );
        setWakeWordEnabled(false);
        setWakeWordListening(false);
        await LocalDb.saveSettings({wakeWordEnabled: false});
        return;
      }
      try {
        await SpeechBridge.startWakeWordService();
        const isRunning = await SpeechBridge.isWakeWordServiceRunning();
        setWakeWordEnabled(true);
        setWakeWordListening(isRunning);
        setWakeWordStopReason(null);
        await LocalDb.saveSettings({wakeWordEnabled: true});
      } catch (e: any) {
        const err = e?.message ?? 'Could not start wake word listener';
        setWakeWordEnabled(false);
        setWakeWordListening(false);
        setWakeWordStopReason(err);
        await LocalDb.saveSettings({wakeWordEnabled: false});
        Alert.alert('Wake word error', err);
      }
    } else {
      try {
        await SpeechBridge.stopWakeWordService();
        setWakeWordEnabled(false);
        setWakeWordListening(false);
        setWakeWordStopReason(null);
        await LocalDb.saveSettings({wakeWordEnabled: false});
      } catch (e: any) {
        Alert.alert(
          'Wake word error',
          e?.message ?? 'Could not stop wake word service',
        );
      }
    }
  };

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
        'Your corrected transcripts will be sent to the server to improve your voice model. No raw audio is sent. You can disable this at any time.',
        [
          {text: 'Cancel', style: 'cancel'},
          {
            text: 'Enable',
            onPress: async () => {
              setCorrectionSyncOptIn(true);
              await LocalDb.saveSettings({correctionSyncOptIn: true});
            },
          },
        ],
      );
    } else {
      setCorrectionSyncOptIn(false);
      await LocalDb.saveSettings({correctionSyncOptIn: false});
    }
  };

  const signOut = async () => {
    await LocalDb.clearAuth();
    await LocalDb.clearActiveAdapters();
    clearAuth();
  };

  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        <Text style={styles.heading}>Settings</Text>

        {/* Navigation shortcut cards */}
        <SettingsItem
          iconName="graphic-eq"
          iconBgColor={colors.iconCircleOrange}
          title="Voice Calibration"
          subtitle="Record phrases to personalize your voice model"
          onPress={() => navigation.navigate('Calibration')}
        />
        <SettingsItem
          iconName="chat"
          iconBgColor={colors.iconCircleBlue}
          title="Quick Phrases"
          subtitle="Manage shortcuts for common tasks"
          onPress={() => navigation.navigate('Phrasebook')}
        />
        <SettingsItem
          iconName="history"
          iconBgColor={colors.iconCirclePurple}
          title="Transcript History"
          subtitle="Review recent speech transcripts"
          onPress={() => navigation.navigate('TranscriptHistory')}
        />
        <SettingsItem
          iconName="supervisor-account"
          iconBgColor={colors.iconCircleGreen}
          title="Caregiver Mode"
          subtitle="PIN-gated view for caregivers"
          onPress={() => navigation.navigate('CaregiverMode')}
        />
        <SettingsItem
          iconName="language"
          iconBgColor={colors.iconCircleGrey}
          title="Language"
          subtitle={preferredLanguage.toUpperCase()}
        />

        {/* Hey Lily Wake Word */}
        <Text style={styles.sectionHeader}>Hey Lily (Wake Word)</Text>
        <View style={styles.card}>
          <View style={styles.rowBetween}>
            <View style={styles.rowLabel}>
              <Text style={styles.cardTitle}>Listen for "Hey Lily"</Text>
              <Text style={styles.cardSub}>
                Hands-free voice commands. Dev builds use "Hey Jarvis" until hey_lily.onnx is trained.
              </Text>
            </View>
            <Switch
              value={wakeWordEnabled}
              onValueChange={toggleWakeWord}
              trackColor={{true: colors.iconCircleOrange, false: '#D1D5DB'}}
              thumbColor={wakeWordEnabled ? colors.orange : '#9CA3AF'}
            />
          </View>
          <View style={styles.statusBox}>
            <View style={styles.statusRow}>
              <View
                style={[
                  styles.statusDot,
                  wakeWordListening
                    ? styles.statusDotActive
                    : styles.statusDotInactive,
                ]}
              />
              <Text
                style={[
                  styles.statusText,
                  wakeWordListening
                    ? styles.statusTextActive
                    : styles.statusTextInactive,
                ]}>
                {wakeWordListening
                  ? 'Listening for "Hey Jarvis" / "Hey Lily"'
                  : 'Service stopped'}
              </Text>
            </View>
            {lastWakeWordEvent && (
              <Text style={styles.lastEventText}>
                Last detected: "{lastWakeWordEvent.model}" (score:{' '}
                {(lastWakeWordEvent.score * 100).toFixed(0)}%) at{' '}
                {new Date(lastWakeWordEvent.timestamp).toLocaleTimeString()}
              </Text>
            )}
            {wakeWordStopReason ? (
              <View style={styles.errorBox}>
                <Text style={styles.errorText}>⚠️ {wakeWordStopReason}</Text>
              </View>
            ) : null}
          </View>
        </View>

        {/* Active Voice Model */}
        <Text style={styles.sectionHeader}>Active Voice Model</Text>
        {activeAdapters.length === 0 ? (
          <View style={styles.card}>
            <Text style={styles.cardSub}>No adapter loaded.</Text>
            <TouchableOpacity
              style={styles.btn}
              onPress={reloadAdapter}
              disabled={refreshing}>
              <Text style={styles.btnText}>
                {refreshing ? 'Loading…' : 'Download Voice Model'}
              </Text>
            </TouchableOpacity>
          </View>
        ) : (
          activeAdapters.map(a => (
            <View key={a.adapterId} style={styles.card}>
              <Text style={styles.cardTitle}>{a.adapterId}</Text>
              <Text style={styles.cardSub}>
                Type: {a.type} · v{a.version}
              </Text>
              <Text style={styles.cardSub} numberOfLines={1}>
                Checksum: {a.checksum.slice(0, 24)}…
              </Text>
            </View>
          ))
        )}

        {/* Voice Activation */}
        <Text style={styles.sectionHeader}>Voice Activation</Text>
        <View style={styles.card}>
          <Text style={styles.cardSub}>
            <Text style={styles.bold}>Primary: </Text>Say "Hey Lily", then your command (e.g. call Ravi).
          </Text>
          <Text style={[styles.cardSub, {marginTop: 8}]}>
            <Text style={styles.bold}>Fallback: </Text>Set VaaniMitra as system voice input and use the keyboard mic.
          </Text>
          <TouchableOpacity
            style={styles.btn}
            onPress={() => SpeechBridge.openVoiceInputSettings()}>
            <Text style={styles.btnText}>Open Voice Input Settings</Text>
          </TouchableOpacity>
        </View>

        {/* Privacy */}
        <Text style={styles.sectionHeader}>Privacy</Text>
        <View style={styles.rowCard}>
          <View style={styles.rowInfo}>
            <Text style={styles.cardTitle}>Correction sync (opt-in)</Text>
            <Text style={styles.cardSub}>
              Send corrected transcripts to improve your model
            </Text>
          </View>
          <Switch
            value={correctionSyncOptIn}
            onValueChange={toggleCorrectionSync}
            trackColor={{true: colors.iconCircleOrange, false: '#D1D5DB'}}
            thumbColor={correctionSyncOptIn ? colors.orange : '#9CA3AF'}
          />
        </View>

        {/* Privacy card */}
        <View style={styles.privacyCard}>
          <View style={styles.privacyIconCircle}>
            <Icon name="lock" size={20} color={colors.white} />
          </View>
          <View style={styles.privacyTextContainer}>
            <Text style={styles.privacyTitle}>
              Your data stays on your device
            </Text>
            <Text style={styles.privacySubtitle}>
              Private • Secure • On-device AI
            </Text>
          </View>
          <Icon name="chevron-right" size={22} color={colors.textMuted} />
        </View>

        {/* Accessibility */}
        <Text style={styles.sectionHeader}>Accessibility Service</Text>
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
              onPress={() => AccessibilityBridge.openAccessibilitySettings()}>
              <Text style={styles.btnText}>Open Accessibility Settings</Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Account */}
        <Text style={styles.sectionHeader}>Account</Text>
        <TouchableOpacity style={styles.signOutBtn} onPress={signOut}>
          <Text style={styles.signOutText}>Sign out</Text>
        </TouchableOpacity>
      </ScrollView>

      <BottomNavigation
        active="settings"
        onHomePress={() => navigation.navigate('Listening')}
        onHistoryPress={() => navigation.navigate('TranscriptHistory')}
        onPhrasesPress={() => navigation.navigate('Phrasebook')}
        onSettingsPress={() => {}}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.screenBackground,
  },
  scrollContent: {
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 24,
  },
  heading: {
    fontSize: 30,
    fontWeight: '800',
    color: colors.textPrimary,
    marginBottom: 20,
  },
  sectionHeader: {
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.2,
    color: colors.iconCircleOrange,
    textTransform: 'uppercase',
    marginTop: 24,
    marginBottom: 10,
  },
  card: {
    backgroundColor: colors.cardBackground,
    borderRadius: 16,
    padding: 16,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: colors.cardBorder,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.04,
    shadowRadius: 3,
    elevation: 1,
  },
  rowCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.cardBackground,
    borderRadius: 16,
    padding: 16,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: colors.cardBorder,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.04,
    shadowRadius: 3,
    elevation: 1,
  },
  rowInfo: {
    flex: 1,
    paddingRight: 12,
  },
  rowBetween: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  rowLabel: {
    flex: 1,
    paddingRight: 12,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
    lineHeight: 20,
  },
  cardSub: {
    fontSize: 13,
    fontWeight: '400',
    color: colors.textSecondary,
    marginTop: 2,
    lineHeight: 18,
  },
  bold: {
    fontWeight: '700',
    color: colors.textPrimary,
  },
  btn: {
    height: 44,
    borderRadius: 22,
    backgroundColor: colors.navy,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 10,
    paddingHorizontal: 16,
  },
  btnText: {
    color: colors.white,
    fontWeight: '700',
    fontSize: 14,
  },
  statusBox: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: colors.cardBorder,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 8,
  },
  statusDotActive: {backgroundColor: '#22C55E'},
  statusDotInactive: {backgroundColor: '#9CA3AF'},
  statusText: {fontSize: 13, fontWeight: '600'},
  statusTextActive: {color: '#16A34A'},
  statusTextInactive: {color: colors.textMuted},
  lastEventText: {
    color: colors.textSecondary,
    fontSize: 12,
    marginTop: 6,
  },
  errorBox: {
    backgroundColor: '#FEF2F2',
    borderRadius: 8,
    padding: 10,
    marginTop: 8,
    borderWidth: 1,
    borderColor: '#FECACA',
  },
  errorText: {color: '#DC2626', fontSize: 12, lineHeight: 16},
  privacyCard: {
    height: 68,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.privacyCardBackground,
    borderRadius: 16,
    paddingHorizontal: 16,
    marginBottom: 10,
    marginTop: 4,
  },
  privacyIconCircle: {
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: colors.navy,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 14,
  },
  privacyTextContainer: {
    flex: 1,
    justifyContent: 'center',
  },
  privacyTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: colors.textPrimary,
    lineHeight: 20,
  },
  privacySubtitle: {
    fontSize: 12,
    fontWeight: '400',
    color: '#8A6D3B',
    marginTop: 2,
    lineHeight: 16,
  },
  signOutBtn: {
    height: 50,
    borderRadius: 25,
    borderWidth: 1.5,
    borderColor: '#FF4D4D',
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 4,
    marginBottom: 16,
  },
  signOutText: {
    color: '#FF4D4D',
    fontWeight: '700',
    fontSize: 15,
  },
});
