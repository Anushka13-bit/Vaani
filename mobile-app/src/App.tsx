import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  PermissionsAndroid,
  Platform,
  StatusBar,
  View,
} from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import DeviceInfo from 'react-native-device-info';

import { backendClient } from './api/trainingBackendClient';
import { LocalDb } from './storage/localDb';
import { useStore } from './state/store';
import { restoreAdaptersOnBoot, syncPhrasebookToNative } from './services/adapterService';
import { SpeechBridge } from './native/SpeechBridge';

import CalibrationScreen from './screens/CalibrationScreen';
import SettingsScreen from './screens/SettingsScreen';
import PhrasebookScreen from './screens/PhrasebookScreen';
import CaregiverModeScreen from './screens/CaregiverModeScreen';
import TranscriptHistoryScreen from './screens/TranscriptHistoryScreen';

export async function requestVoicePermissions(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  const permissions: string[] = [PermissionsAndroid.PERMISSIONS.RECORD_AUDIO];
  if (Platform.Version >= 33) {
    permissions.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }
  try {
    const granted: Record<string, string> = await PermissionsAndroid.requestMultiple(permissions as any);
    return permissions.every(p => granted[p] === PermissionsAndroid.RESULTS.GRANTED);
  } catch (err) {
    console.warn('[Permissions] Failed to request permissions:', err);
    return false;
  }
}

export type RootStackParamList = {
  Calibration: undefined;
  Settings: undefined;
  Phrasebook: undefined;
  CaregiverMode: undefined;
  TranscriptHistory: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

const DARK_THEME = {
  dark: true,
  colors: {
    primary: '#6C63FF',
    background: '#0F0F1A',
    card: '#1A1A2E',
    text: '#E8E8FF',
    border: '#2A2A3E',
    notification: '#6C63FF',
  },
};

export default function App() {
  const {
    setAuth,
    setPreferredLanguage,
    setCorrectionSyncOptIn,
    setActiveAdapters,
    setEntries,
    setWakeWordEnabled,
    setWakeWordListening,
    setLastWakeWordEvent,
    setWakeWordStopReason,
  } = useStore();
  const [ready, setReady] = useState(false);

  // Wire up wake word event listeners
  useEffect(() => {
    const subDetected = SpeechBridge.onWakeWordDetected((payload) => {
      console.log('[App] Wake word detected:', payload);
      setLastWakeWordEvent({
        model: payload.model,
        score: payload.score,
        timestamp: Date.now(),
      });
    });

    const subError = SpeechBridge.onWakeWordError((payload) => {
      console.warn('[App] Wake word error:', payload.reason);
      setWakeWordListening(false);
      setWakeWordStopReason(payload.reason);
    });

    return () => {
      subDetected?.remove();
      subError?.remove();
    };
  }, [setLastWakeWordEvent, setWakeWordListening, setWakeWordStopReason]);

  useEffect(() => {
    (async () => {
      try {
        const settings = await LocalDb.loadSettings();
        if (settings?.preferredLanguage) setPreferredLanguage(settings.preferredLanguage as string);
        if (settings?.correctionSyncOptIn) setCorrectionSyncOptIn(settings.correctionSyncOptIn as boolean);

        const shouldEnableWakeWord = settings?.wakeWordEnabled !== undefined
          ? Boolean(settings.wakeWordEnabled)
          : true;
        setWakeWordEnabled(shouldEnableWakeWord);

        try {
          const auth = await LocalDb.loadAuth();
          if (auth && new Date(auth.expiresAt) > new Date()) {
            backendClient.setToken(auth.accessToken);
            setAuth(auth.userId, auth.accessToken);
          } else {
            const deviceId = await DeviceInfo.getUniqueId();
            const resp = await backendClient.auth.registerDevice({
              device_id: deviceId,
              preferred_language: 'en',
            });
            await LocalDb.saveAuth({
              userId: resp.user_id,
              accessToken: resp.access_token,
              expiresAt: resp.expires_at,
            });
            setAuth(resp.user_id, resp.access_token);
          }
        } catch (authErr: any) {
          console.warn('[App] Training backend unreachable — operating in offline mode:', authErr?.message ?? authErr);
          try {
            const deviceId = await DeviceInfo.getUniqueId();
            setAuth(`local_${deviceId.slice(0, 8)}`, '');
          } catch {
            setAuth('local_user', '');
          }
        }

        const adapters = await restoreAdaptersOnBoot();
        if (adapters.length > 0) setActiveAdapters(adapters);

        const phrasebook = await LocalDb.loadPhrasebook();
        setEntries(phrasebook);
        await syncPhrasebookToNative(phrasebook);

        // Check & request permissions before auto-starting wake word service
        if (shouldEnableWakeWord) {
          let hasPermissions = await SpeechBridge.checkVoicePermissions();
          if (!hasPermissions) {
            hasPermissions = await requestVoicePermissions();
          }

          if (hasPermissions) {
            try {
              const running = await SpeechBridge.isWakeWordServiceRunning();
              if (!running) {
                await SpeechBridge.startWakeWordService();
              }
              const isNowRunning = await SpeechBridge.isWakeWordServiceRunning();
              setWakeWordListening(isNowRunning);
            } catch (e: any) {
              console.warn('[App] Wake word service not started:', e);
              setWakeWordListening(false);
              setWakeWordStopReason(e?.message ?? 'Failed to start wake word service');
            }
          } else {
            console.warn('[App] Voice permissions denied — not starting wake-word service');
            setWakeWordListening(false);
            setWakeWordEnabled(false);
            const reason = 'Microphone and Notification permissions not granted';
            setWakeWordStopReason(reason);
            Alert.alert(
              'Permissions Required',
              'VaaniMitra requires Microphone and Notification permissions for hands-free voice activation ("Hey Lily"). Please grant permissions in Settings to enable wake word listening.',
            );
          }
        } else {
          setWakeWordListening(false);
        }
      } catch (e) {
        console.error('[App] Bootstrap failed:', e);
      } finally {
        setReady(true);
      }
    })();
  }, [
    setActiveAdapters,
    setAuth,
    setCorrectionSyncOptIn,
    setEntries,
    setPreferredLanguage,
    setWakeWordEnabled,
    setWakeWordListening,
    setWakeWordStopReason,
  ]);

  if (!ready) {
    return (
      <View style={{ flex: 1, backgroundColor: '#0F0F1A', justifyContent: 'center', alignItems: 'center' }}>
        <ActivityIndicator size="large" color="#6C63FF" />
      </View>
    );
  }

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor="#0F0F1A" />
      <NavigationContainer theme={DARK_THEME as any}>
        <Stack.Navigator
          initialRouteName="Calibration"
          screenOptions={{
            headerStyle: { backgroundColor: '#1A1A2E' },
            headerTintColor: '#E8E8FF',
            headerTitleStyle: { fontWeight: '700' },
          }}
        >
          <Stack.Screen
            name="Calibration"
            component={CalibrationScreen}
            options={{ title: 'VaaniMitra — Setup' }}
          />
          <Stack.Screen
            name="Settings"
            component={SettingsScreen}
            options={{ title: 'Settings' }}
          />
          <Stack.Screen
            name="Phrasebook"
            component={PhrasebookScreen}
            options={{ title: 'Phrasebook' }}
          />
          <Stack.Screen
            name="CaregiverMode"
            component={CaregiverModeScreen}
            options={{ title: 'Caregiver Mode' }}
          />
          <Stack.Screen
            name="TranscriptHistory"
            component={TranscriptHistoryScreen}
            options={{ title: 'Transcript History' }}
          />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
