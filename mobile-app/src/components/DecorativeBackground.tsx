import React from 'react';
import {View, StyleSheet, useWindowDimensions} from 'react-native';
import {colors} from '../theme/colors';

/**
 * Decorative backdrop for the Welcome screen:
 * - Warm cream screen background (#FFF9F2)
 * - Top-right soft peach organic curved backdrop
 * - Double-layer clean organic orange waves occupying the lower portion
 * Halftone dot-grid effect has been completely removed per reference UI.
 */
const DecorativeBackground: React.FC = () => {
  const {width, height} = useWindowDimensions();

  return (
    <View pointerEvents="none" style={StyleSheet.absoluteFill}>
      {/* Base warm cream fill */}
      <View style={[StyleSheet.absoluteFill, {backgroundColor: colors.cream}]} />

      {/* Top-right soft organic cream/peach swoop behind badge */}
      <View
        style={[
          styles.topSwoop,
          {
            width: width * 1.1,
            height: height * 0.42,
            right: -width * 0.25,
            top: -height * 0.05,
            borderBottomLeftRadius: width * 0.6,
          },
        ]}
      />

      {/* Layer 1 (Back wave) — warm lighter orange */}
      <View
        style={[
          styles.waveBack,
          {
            width: width * 1.5,
            height: height * 0.32,
            bottom: -height * 0.04,
            left: -width * 0.25,
            borderTopLeftRadius: width * 0.8,
            borderTopRightRadius: width * 0.7,
          },
        ]}
      />

      {/* Layer 2 (Front wave) — vibrant deeper orange */}
      <View
        style={[
          styles.waveFront,
          {
            width: width * 1.4,
            height: height * 0.22,
            bottom: -height * 0.05,
            left: -width * 0.2,
            borderTopLeftRadius: width * 0.75,
            borderTopRightRadius: width * 0.65,
          },
        ]}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  topSwoop: {
    position: 'absolute',
    backgroundColor: '#FDEEDF',
    opacity: 0.85,
  },
  waveBack: {
    position: 'absolute',
    backgroundColor: '#FFA726',
  },
  waveFront: {
    position: 'absolute',
    backgroundColor: '#FF7043',
  },
});

export default DecorativeBackground;
