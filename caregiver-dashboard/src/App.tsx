import React, { useState, useEffect } from 'react';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8000/v1';

interface TranscriptEntry {
  id: string;
  transcript: string;
  confidence: number;
  timestamp: string;
}

const api = axios.create({ baseURL: API_BASE });

// ── Auth ──────────────────────────────────────────────────────────────────────

function LoginScreen({ onLogin }: { onLogin: (token: string, userId: string) => void }) {
  const [deviceId, setDeviceId] = useState('caregiver-web-' + Math.random().toString(36).slice(2, 8));
  const [userId, setUserId] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = async () => {
    setLoading(true);
    setError('');
    try {
      const { data } = await api.post('/auth/device', {
        device_id: deviceId,
        preferred_language: 'en',
      });
      api.defaults.headers.common['Authorization'] = `Bearer ${data.access_token}`;
      onLogin(data.access_token, userId || data.user_id);
    } catch (e: any) {
      setError(e?.response?.data?.detail ?? e?.message ?? 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={styles.center}>
      <div style={styles.card}>
        <h1 style={styles.logo}>🗣️ VaaniMitra</h1>
        <p style={styles.subtitle}>Caregiver Dashboard</p>

        <label style={styles.label}>User ID to monitor</label>
        <input
          style={styles.input}
          value={userId}
          onChange={e => setUserId(e.target.value)}
          placeholder="Paste the user_id from the mobile app"
        />

        {error && <p style={{ color: 'var(--danger)', fontSize: 13, marginTop: 8 }}>{error}</p>}

        <button style={styles.btn} onClick={handleLogin} disabled={loading}>
          {loading ? 'Connecting…' : 'Connect to backend'}
        </button>

        <p style={{ color: 'var(--text-dim)', fontSize: 11, marginTop: 16, textAlign: 'center' }}>
          Backend: <code>{API_BASE}</code>
        </p>
      </div>
    </div>
  );
}

// ── Transcripts panel ─────────────────────────────────────────────────────────

function TranscriptsPanel({ userId }: { userId: string }) {
  const [transcripts, setTranscripts] = useState<TranscriptEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [confidenceFilter, setConfidenceFilter] = useState(0.6);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const { data } = await api.get(`/caregiver/${userId}/transcripts`, {
        params: { confidence_lt: confidenceFilter, limit: 50 },
      });
      setTranscripts(data.transcripts);
    } catch (e: any) {
      setError(e?.response?.data?.detail ?? e?.message ?? 'Failed to load transcripts');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [userId, confidenceFilter]);

  return (
    <div>
      <div style={styles.filterRow}>
        <label style={styles.label}>Show confidence below</label>
        <input
          type="range" min="0.1" max="1" step="0.05"
          value={confidenceFilter}
          onChange={e => setConfidenceFilter(parseFloat(e.target.value))}
          style={{ flex: 1 }}
        />
        <span style={{ color: 'var(--accent)', fontWeight: 600, minWidth: 36 }}>
          {Math.round(confidenceFilter * 100)}%
        </span>
        <button style={{ ...styles.btn, padding: '8px 16px', marginLeft: 12 }} onClick={load}>
          Refresh
        </button>
      </div>

      {error && <p style={{ color: 'var(--danger)', marginBottom: 12 }}>{error}</p>}

      {loading ? (
        <p style={{ color: 'var(--text-dim)' }}>Loading…</p>
      ) : transcripts.length === 0 ? (
        <p style={{ color: 'var(--text-dim)' }}>
          No low-confidence transcripts found. Try raising the threshold.
        </p>
      ) : (
        <div style={styles.list}>
          {transcripts.map(t => (
            <div key={t.id} style={styles.transcriptCard}>
              <div style={styles.transcriptMeta}>
                <span style={styles.confidenceBadge}>
                  {Math.round(t.confidence * 100)}% confidence
                </span>
                <span style={{ color: 'var(--text-dim)', fontSize: 12 }}>
                  {new Date(t.timestamp).toLocaleString()}
                </span>
              </div>
              <p style={styles.transcriptText}>{t.transcript}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── App root ──────────────────────────────────────────────────────────────────

export default function App() {
  const [token, setToken] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [tab, setTab] = useState<'transcripts' | 'phrasebook'>('transcripts');

  if (!token) {
    return <LoginScreen onLogin={(t, uid) => { setToken(t); setUserId(uid); }} />;
  }

  return (
    <div style={styles.shell}>
      {/* Sidebar */}
      <aside style={styles.sidebar}>
        <div style={styles.sidebarLogo}>🗣️ VaaniMitra</div>
        <p style={{ color: 'var(--text-dim)', fontSize: 12, marginBottom: 24 }}>
          Caregiver Dashboard
        </p>

        <nav>
          {(['transcripts', 'phrasebook'] as const).map(t => (
            <button
              key={t}
              style={{ ...styles.navBtn, ...(tab === t ? styles.navBtnActive : {}) }}
              onClick={() => setTab(t)}
            >
              {t === 'transcripts' ? '📋 Transcripts' : '📖 Phrasebook'}
            </button>
          ))}
        </nav>

        <div style={{ marginTop: 'auto', paddingTop: 24 }}>
          <p style={{ color: 'var(--text-dim)', fontSize: 11 }}>
            Monitoring user:<br />
            <code style={{ color: 'var(--accent)', wordBreak: 'break-all' }}>{userId}</code>
          </p>
          <button
            style={{ ...styles.btn, backgroundColor: '#333', marginTop: 12, width: '100%' }}
            onClick={() => { setToken(null); setUserId(''); }}
          >
            Disconnect
          </button>
        </div>
      </aside>

      {/* Main */}
      <main style={styles.main}>
        <h2 style={styles.pageTitle}>
          {tab === 'transcripts' ? 'Low-Confidence Transcripts' : 'Phrasebook'}
        </h2>

        {tab === 'transcripts' && <TranscriptsPanel userId={userId} />}
        {tab === 'phrasebook' && (
          <p style={{ color: 'var(--text-dim)' }}>
            Phrasebook editing coming soon — use the mobile app for now.
          </p>
        )}
      </main>
    </div>
  );
}

// ── Inline styles ─────────────────────────────────────────────────────────────

const styles: Record<string, React.CSSProperties> = {
  center: {
    minHeight: '100vh', display: 'flex',
    alignItems: 'center', justifyContent: 'center',
  },
  card: {
    background: 'var(--surface)', borderRadius: 20,
    padding: 40, width: 420, display: 'flex', flexDirection: 'column', gap: 12,
    boxShadow: '0 8px 40px rgba(108,99,255,0.2)',
  },
  logo: { fontSize: 28, fontWeight: 700, color: 'var(--text)' },
  subtitle: { color: 'var(--text-dim)', fontSize: 14, marginBottom: 8 },
  label: { fontSize: 12, color: 'var(--text-dim)', fontWeight: 500 },
  input: {
    background: '#0f0f1a', color: 'var(--text)',
    border: '1px solid #333', borderRadius: 8,
    padding: '12px 14px', fontSize: 14, width: '100%',
  },
  btn: {
    background: 'var(--accent)', color: '#fff',
    border: 'none', borderRadius: 10,
    padding: '12px 20px', fontSize: 14,
    fontWeight: 600, cursor: 'pointer', marginTop: 8,
  },
  shell: { display: 'flex', minHeight: '100vh' },
  sidebar: {
    width: 220, background: 'var(--surface)', padding: 24,
    display: 'flex', flexDirection: 'column', gap: 8,
    borderRight: '1px solid #2a2a3e',
  },
  sidebarLogo: { fontSize: 20, fontWeight: 700, marginBottom: 4 },
  navBtn: {
    display: 'block', width: '100%', textAlign: 'left',
    background: 'transparent', color: 'var(--text-dim)',
    padding: '10px 12px', borderRadius: 8, fontSize: 14,
    cursor: 'pointer', border: 'none',
  },
  navBtnActive: {
    background: 'var(--accent-dim)', color: 'var(--accent)', fontWeight: 600,
  },
  main: { flex: 1, padding: 32, overflowY: 'auto' },
  pageTitle: { fontSize: 22, fontWeight: 700, marginBottom: 24 },
  filterRow: {
    display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20,
  },
  list: { display: 'flex', flexDirection: 'column', gap: 12 },
  transcriptCard: {
    background: 'var(--surface)', borderRadius: 12, padding: 16,
  },
  transcriptMeta: {
    display: 'flex', gap: 12, alignItems: 'center', marginBottom: 8,
  },
  confidenceBadge: {
    background: 'var(--accent-dim)', color: 'var(--accent)',
    padding: '2px 10px', borderRadius: 20, fontSize: 12, fontWeight: 600,
  },
  transcriptText: { fontSize: 15, lineHeight: 1.5 },
};
