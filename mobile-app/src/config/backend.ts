import DeviceInfo from 'react-native-device-info';
import { Platform } from 'react-native';

/**
 * Backend URL for local FastAPI (laptop) during development.
 *
 * Automatically detects:
 * - Android emulator → uses 10.0.2.2
 * - Physical device over USB (adb reverse) → uses 127.0.0.1
 */
const getDevHost = (): string => {
  try {
    if (Platform.OS === 'android' && DeviceInfo.isEmulatorSync()) {
      return '10.0.2.2';
    }
  } catch {
    // fallback
  }
  return '127.0.0.1';
};

export const API_HOST = __DEV__ ? getDevHost() : 'your-production-server.example.com';

export const API_PORT = 8000;

export const API_BASE_URL = `http://${API_HOST}:${API_PORT}/v1`;
