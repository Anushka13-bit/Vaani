import React, {useState, useRef, useMemo, useCallback} from 'react';
import {View, Text, StyleSheet, Alert} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import AudioRecorderPlayer from 'react-native-audio-recorder-player';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import type {RootStackParamList} from '../navigation/types';
import {colors} from '../theme/colors';
import MicrophoneButton from '../components/MicrophoneButton';
import VoiceWaveform from '../components/VoiceWaveform';
import BottomNavigation from '../components/BottomNavigation';
import {useStore} from '../state/store';
import {requestVoicePermissions} from '../App';
import {SpeechBridge} from '../native/SpeechBridge';

type Props = NativeStackScreenProps<RootStackParamList, 'Listening'>;

const EXAMPLE_PHRASES = [
  '"Send a message to Ravi"',
  '"Set an alarm for 7 AM"',
  '"Open WhatsApp"',
];

/**
 * Main voice dashboard screen.
 * Tapping the microphone button records your voice, displays active recording
 * duration, and runs on-device transcription with clear status feedback.
 */
const ListeningScreen: React.FC<Props> = ({navigation}) => {
  const {wakeWordListening} = useStore();

  const [isRecording, setIsRecording] = useState(false);
  const [recordSecs, setRecordSecs] = useState(0);
  const [lastTranscript, setLastTranscript] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  const recorder = useMemo(() => new AudioRecorderPlayer(), []);
  const timerRef = useRef<any>(null);

  const handleMicPress = useCallback(async () => {
    if (isRecording) {
      // Stop recording
      try {
        if (timerRef.current) {
          clearInterval(timerRef.current);
          timerRef.current = null;
        }
        const resultUri = await recorder.stopRecorder();
        recorder.removeRecordBackListener();
        setIsRecording(false);
        setStatusMessage('Processing speech…');

        // Attempt transcription via native SpeechBridge
        try {
          const res = await SpeechBridge.transcribeFile(resultUri);
          if (res?.text) {
            setLastTranscript(res.text);
            setStatusMessage(`Heard: "${res.text}"`);
          } else {
            setStatusMessage('Voice recorded successfully!');
          }
        } catch {
          setStatusMessage('Audio recorded & saved!');
        }
      } catch (err: any) {
        setIsRecording(false);
        setStatusMessage(null);
        Alert.alert('Recording error', err?.message ?? 'Could not stop recording');
      }
    } else {
      // Start recording
      const hasPerm = await requestVoicePermissions();
      if (!hasPerm) {
        Alert.alert(
          'Microphone Permission Required',
          'Please allow microphone access to record your voice.',
        );
        return;
      }
      try {
        setLastTranscript(null);
        setStatusMessage('Recording… Tap mic again to stop');
        setRecordSecs(0);
        await recorder.startRecorder();
        setIsRecording(true);
        timerRef.current = setInterval(() => {
          setRecordSecs(s => s + 1);
        }, 1000);
      } catch (err: any) {
        setIsRecording(false);
        setStatusMessage(null);
        Alert.alert('Recording error', err?.message ?? 'Could not start recording');
      }
    }
  }, [isRecording, recorder]);

  const getHeading = () => {
    if (isRecording) return `Recording… (${recordSecs}s)`;
    if (statusMessage) return 'Voice Input';
    if (wakeWordListening) return 'Listening…';
    return 'Ready';
  };

  const getSubtitle = () => {
    if (isRecording) return 'Speak now • Tap mic again to stop';
    if (statusMessage) return statusMessage;
    if (wakeWordListening) return 'Say "Hey Lily" or tap the mic';
    return 'Tap the mic to speak';
  };

  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
      <View style={styles.content}>
        {/* Header */}
        <View style={styles.header}>
          <Text
            style={[
              styles.heading,
              isRecording && {color: colors.red},
            ]}>
            {getHeading()}
          </Text>
          <Text
            style={[
              styles.subtitle,
              isRecording && {color: colors.red, fontWeight: '600'},
            ]}>
            {getSubtitle()}
          </Text>
        </View>

        {/* Mic + Waveform */}
        <View style={styles.centerSection}>
          <MicrophoneButton onPress={handleMicPress} />
          <View style={styles.waveformWrapper}>
            <VoiceWaveform />
          </View>
        </View>

        {/* Transcript or Try saying */}
        {lastTranscript ? (
          <View style={styles.transcriptCard}>
            <Text style={styles.transcriptLabel}>Transcribed Speech</Text>
            <Text style={styles.transcriptText}>"{lastTranscript}"</Text>
          </View>
        ) : (
          <View style={styles.trySayingArea}>
            <Text style={styles.tryLabel}>Try saying</Text>
            {EXAMPLE_PHRASES.map(phrase => (
              <Text key={phrase} style={styles.phraseText}>
                {phrase}
              </Text>
            ))}
          </View>
        )}
      </View>

      <BottomNavigation
        active="home"
        onHomePress={() => {}}
        onHistoryPress={() => navigation.navigate('TranscriptHistory')}
        onPhrasesPress={() => navigation.navigate('Phrasebook')}
        onSettingsPress={() => navigation.navigate('Settings')}
      />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.screenBackground,
  },
  content: {
    flex: 1,
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingTop: 24,
    paddingBottom: 24,
  },
  header: {
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  heading: {
    fontSize: 26,
    fontWeight: '800',
    color: colors.textPrimary,
    textAlign: 'center',
    letterSpacing: -0.3,
  },
  subtitle: {
    fontSize: 15,
    fontWeight: '400',
    color: colors.textSecondary,
    marginTop: 6,
    textAlign: 'center',
  },
  centerSection: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  waveformWrapper: {
    marginTop: 24,
    alignItems: 'center',
  },
  trySayingArea: {
    alignItems: 'center',
    paddingHorizontal: 20,
  },
  tryLabel: {
    fontSize: 14,
    fontWeight: '400',
    color: colors.textSecondary,
    marginBottom: 12,
    textAlign: 'center',
  },
  phraseText: {
    fontSize: 15,
    fontWeight: '600',
    color: colors.textPrimary,
    marginBottom: 10,
    textAlign: 'center',
  },
  transcriptCard: {
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    padding: 16,
    marginHorizontal: 24,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#E5E7EB',
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.05,
    shadowRadius: 4,
    elevation: 2,
    maxWidth: '88%',
  },
  transcriptLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: colors.textSecondary,
    textTransform: 'uppercase',
    letterSpacing: 1,
    marginBottom: 6,
  },
  transcriptText: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
    textAlign: 'center',
  },
});

export default ListeningScreen;
