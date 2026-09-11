import React from 'react';
import {View, StyleSheet} from 'react-native';
import {colors} from '../theme/colors';

interface VoiceWaveformProps {
  height?: number;
}

/**
 * Deterministic symmetrical voice waveform matching the reference UI.
 * Symmetrical around a prominent center peak with harmonic tapering,
 * rendered in uniform #FF4D4D.
 */
const SYMMETRICAL_BAR_HEIGHTS = [
  4, 8, 12, 6, 16, 10, 22, 14, 28, 18, 32, 22, 36, // Left side (13 bars)
  40,                                                // Center bar (peak)
  36, 22, 32, 18, 28, 14, 22, 10, 16, 6, 12, 8, 4,  // Right side (13 bars)
];

const VoiceWaveform: React.FC<VoiceWaveformProps> = ({height = 40}) => {
  const maxBar = 40;
  const scale = height / maxBar;

  return (
    <View style={[styles.container, {height}]}>
      {SYMMETRICAL_BAR_HEIGHTS.map((barHeight, index) => (
        <View
          key={index}
          style={[
            styles.bar,
            {
              height: Math.max(3, barHeight * scale),
              backgroundColor: colors.red,
            },
          ]}
        />
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
  },
  bar: {
    width: 3,
    borderRadius: 2,
  },
});

export default VoiceWaveform;
