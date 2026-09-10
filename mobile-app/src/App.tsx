/**
 * VaaniMitra — App.tsx
 */
import React, { useEffect, useState } from 'react';
import { ActivityIndicator, StatusBar, View } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import DeviceInfo from 'react-native-device-info';

import { backendClient } from './api/trainingBackendClient';
import { LocalDb } from './storage/localDb';
import { useStore } from './state/store';
import { restoreAdaptersOnBoot, syncPhrasebookToNative } from './services/adapterService';

import CalibrationScreen from './screens/CalibrationScreen';
import SettingsScreen from './screens/SettingsScreen';
import PhrasebookScreen from './screens/PhrasebookScreen';
import CaregiverModeScreen from './screens/CaregiverModeScreen';
import TranscriptHistoryScreen from './screens/TranscriptHistoryScreen';

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
  const { setAuth, setPreferredLanguage, setCorrectionSyncOptIn, setActiveAdapters, setEntries } = useStore();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const settings = await LocalDb.loadSettings();
        if (settings?.preferredLanguage) setPreferredLanguage(settings.preferredLanguage as string);
        if (settings?.correctionSyncOptIn) setCorrectionSyncOptIn(settings.correctionSyncOptIn as boolean);

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

        const adapters = await restoreAdaptersOnBoot();
        if (adapters.length > 0) setActiveAdapters(adapters);

        const phrasebook = await LocalDb.loadPhrasebook();
        setEntries(phrasebook);
        await syncPhrasebookToNative(phrasebook);

        // Start "Hey Lily" wake-word listener if adapters are ready
        try {
          const { SpeechBridge } = await import('./native/SpeechBridge');
          const running = await SpeechBridge.isWakeWordServiceRunning();
          if (!running) await SpeechBridge.startWakeWordService();
        } catch (e) {
          console.warn('[App] Wake word service not started:', e);
        }
      } catch (e) {
        console.error('[App] Bootstrap failed:', e);
      } finally {
        setReady(true);
      }
    })();
  }, []);

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
