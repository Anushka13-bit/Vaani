/**
 * Backend URL for local FastAPI (laptop) during development.
 *
 * Setup options:
 * 1. Android emulator → host machine:
 *      API_HOST = '10.0.2.2'
 * 2. Physical device + USB (recommended for demo):
 *      adb reverse tcp:8000 tcp:8000
 *      API_HOST = '127.0.0.1'
 * 3. Phone hotspot → laptop LAN IP:
 *      API_HOST = '192.168.x.x'  (your laptop's IP on the hotspot network)
 *
 * Never point at a cloud host for calibration — audio stays on your laptop.
 */
export const API_HOST = __DEV__ ? '127.0.0.1' : 'your-production-server.example.com';

export const API_PORT = 8000;

export const API_BASE_URL = `http://${API_HOST}:${API_PORT}/v1`;
