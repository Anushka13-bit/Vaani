import React, {useEffect, useRef} from 'react';
import {Animated, Easing, StyleSheet, View} from 'react-native';

interface RecordingOrbProps {
  isRecording?: boolean;
  metering?: number;
  size?: number;
}

const PETAL_COUNT = 10;
const PETAL_ANGLES = Array.from({length: PETAL_COUNT}, (_, i) => (i * 360) / PETAL_COUNT);

// Translucent warm golden and coral shades matching the reference sunflower bloom
const PETAL_COLORS = [
  'rgba(255, 183, 77, 0.76)',  // Soft warm golden
  'rgba(255, 167, 38, 0.74)',  // Primary vibrant orange
  'rgba(255, 138, 101, 0.72)', // Delicate coral-peach
  'rgba(255, 171, 64, 0.75)',  // Warm amber
];

/**
 * Minimalist rotating sunflower orb inspired by modern voice assistants and the
 * reference artwork:
 * - 10 swirling translucent petals forming a radiant pinwheel aperture
 * - Clean translucent glowing glass rim with subtle white-gold highlight
 * - 3 soft concentric hairline ripple rings radiating into the background
 * - Continuous smooth 2.4s rotation, gentle breathing pulse, and audio-reactive scaling
 */
const RecordingOrb: React.FC<RecordingOrbProps> = ({
  isRecording = true,
  metering,
  size = 84,
}) => {
  const rotateAnim = useRef(new Animated.Value(0)).current;
  const breathAnim = useRef(new Animated.Value(0)).current;
  const rippleAnim = useRef(new Animated.Value(0)).current;
  const audioAnim = useRef(new Animated.Value(0)).current;

  // 1. Continuous smooth rotation for the sunflower petals (2.4s per revolution)
  useEffect(() => {
    if (!isRecording) return;
    const rotateLoop = Animated.loop(
      Animated.timing(rotateAnim, {
        toValue: 1,
        duration: 2400,
        easing: Easing.linear,
        useNativeDriver: true,
      }),
    );
    rotateLoop.start();
    return () => rotateLoop.stop();
  }, [isRecording, rotateAnim]);

  // 2. Gentle breathing pulse for the core orb
  useEffect(() => {
    if (!isRecording) return;
    const breathLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(breathAnim, {
          toValue: 1,
          duration: 1300,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(breathAnim, {
          toValue: 0,
          duration: 1300,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ]),
    );
    breathLoop.start();
    return () => breathLoop.stop();
  }, [isRecording, breathAnim]);

  // 3. Subtle concentric ripples radiating behind the orb
  useEffect(() => {
    if (!isRecording) return;
    const rippleLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(rippleAnim, {
          toValue: 1,
          duration: 1800,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
        Animated.timing(rippleAnim, {
          toValue: 0,
          duration: 1800,
          easing: Easing.inOut(Easing.ease),
          useNativeDriver: true,
        }),
      ]),
    );
    rippleLoop.start();
    return () => rippleLoop.stop();
  }, [isRecording, rippleAnim]);

  // 4. Smooth audio-reactive response
  useEffect(() => {
    if (metering !== undefined && metering > -160) {
      // Normalize dB to [0, 1] range smoothly
      const normalized = Math.max(0, Math.min(1, (metering + 55) / 55));
      Animated.timing(audioAnim, {
        toValue: normalized,
        duration: 80,
        easing: Easing.out(Easing.ease),
        useNativeDriver: true,
      }).start();
    } else {
      Animated.timing(audioAnim, {
        toValue: 0,
        duration: 140,
        useNativeDriver: true,
      }).start();
    }
  }, [metering, audioAnim]);

  const spin = rotateAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0deg', '360deg'],
  });

  const baseScale = breathAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [0.97, 1.04],
  });

  const audioScale = audioAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [1, 1.15],
  });

  const rippleScale1 = rippleAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [0.98, 1.04],
  });

  const rippleScale2 = rippleAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [1.0, 1.07],
  });

  const rippleScale3 = rippleAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [1.02, 1.10],
  });

  const rippleOpacity = rippleAnim.interpolate({
    inputRange: [0, 0.5, 1],
    outputRange: [0.35, 0.55, 0.35],
  });

  // Proportions matching the reference artwork
  const orbDiameter = size * 1.25;
  const ring1 = orbDiameter * 1.35;
  const ring2 = orbDiameter * 1.68;
  const ring3 = orbDiameter * 2.05;

  return (
    <View style={[styles.wrapper, {width: ring3, height: ring3}]}>
      {/* Outer Glow Halo */}
      <View
        style={[
          styles.glowBackdrop,
          {
            width: orbDiameter * 1.2,
            height: orbDiameter * 1.2,
            borderRadius: (orbDiameter * 1.2) / 2,
          },
        ]}
      />

      {/* Ripple Ring 3 (Outermost hairline) */}
      <Animated.View
        style={[
          styles.hairlineRing,
          {
            width: ring3,
            height: ring3,
            borderRadius: ring3 / 2,
            opacity: rippleOpacity,
            transform: [{scale: rippleScale3}],
          },
        ]}
      />

      {/* Ripple Ring 2 (Middle hairline) */}
      <Animated.View
        style={[
          styles.hairlineRing,
          {
            width: ring2,
            height: ring2,
            borderRadius: ring2 / 2,
            opacity: rippleOpacity,
            transform: [{scale: rippleScale2}],
          },
        ]}
      />

      {/* Ripple Ring 1 (Innermost hairline) */}
      <Animated.View
        style={[
          styles.hairlineRing,
          {
            width: ring1,
            height: ring1,
            borderRadius: ring1 / 2,
            opacity: rippleOpacity,
            transform: [{scale: rippleScale1}],
          },
        ]}
      />

      {/* Glass Orb Outer Shell & Glow */}
      <Animated.View
        style={[
          styles.orbShell,
          {
            width: orbDiameter,
            height: orbDiameter,
            borderRadius: orbDiameter / 2,
            transform: [{scale: baseScale}, {scale: audioScale}],
          },
        ]}>
        {/* Soft luminous inner gradient fill */}
        <View
          style={[
            styles.innerGlassFill,
            {
              width: orbDiameter - 6,
              height: orbDiameter - 6,
              borderRadius: (orbDiameter - 6) / 2,
            },
          ]}
        />

        {/* Rotating Sunflower Bloom */}
        <Animated.View
          style={[
            styles.sunflowerBloom,
            {
              width: orbDiameter * 0.75,
              height: orbDiameter * 0.75,
              transform: [{rotate: spin}],
            },
          ]}>
          {PETAL_ANGLES.map((angle, index) => {
            const petalColor = PETAL_COLORS[index % PETAL_COLORS.length];
            return (
              <View
                key={angle}
                style={[
                  styles.petal,
                  {
                    backgroundColor: petalColor,
                    transform: [
                      {rotate: `${angle}deg`},
                      {translateY: -orbDiameter * 0.19},
                      {rotate: '15deg'}, // graceful clockwise swirl angle
                    ],
                  },
                ]}
              />
            );
          })}

          {/* Luminous Aperture Center ("Eye" of the flower) */}
          <View
            style={[
              styles.flowerEye,
              {
                width: orbDiameter * 0.18,
                height: orbDiameter * 0.18,
                borderRadius: (orbDiameter * 0.18) / 2,
              },
            ]}
          />
        </Animated.View>
      </Animated.View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  glowBackdrop: {
    position: 'absolute',
    backgroundColor: '#FFA726',
    opacity: 0.18,
    shadowColor: '#FF7043',
    shadowOffset: {width: 0, height: 0},
    shadowOpacity: 0.5,
    shadowRadius: 28,
    elevation: 6,
  },
  hairlineRing: {
    position: 'absolute',
    borderWidth: 1.5,
    borderColor: '#FFBFA8',
  },
  orbShell: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#FFF7EF',
    borderWidth: 2.5,
    borderColor: 'rgba(255, 255, 255, 0.95)',
    shadowColor: '#FFA726',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.35,
    shadowRadius: 14,
    elevation: 8,
  },
  innerGlassFill: {
    position: 'absolute',
    backgroundColor: 'rgba(255, 237, 218, 0.45)',
    borderWidth: 1,
    borderColor: 'rgba(255, 183, 77, 0.25)',
  },
  sunflowerBloom: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
  },
  petal: {
    position: 'absolute',
    width: 20,
    height: 42,
    borderTopLeftRadius: 18,
    borderTopRightRadius: 18,
    borderBottomRightRadius: 12,
    borderBottomLeftRadius: 6,
    shadowColor: '#FF7043',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.25,
    shadowRadius: 3,
    elevation: 1,
  },
  flowerEye: {
    position: 'absolute',
    backgroundColor: '#FFFFFF',
    opacity: 0.9,
    shadowColor: '#FFA726',
    shadowOffset: {width: 0, height: 0},
    shadowOpacity: 0.6,
    shadowRadius: 6,
    elevation: 2,
  },
});

export default RecordingOrb;
