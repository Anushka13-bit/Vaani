import React, {useEffect, useRef} from 'react';
import {Animated, TouchableOpacity, View, StyleSheet} from 'react-native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import {colors} from '../theme/colors';

interface MicrophoneButtonProps {
  onPress?: () => void;
  size?: number;
}

/**
 * Central microphone button with concentric soft peach rings matching the reference UI.
 */
const MicrophoneButton: React.FC<MicrophoneButtonProps> = ({
  onPress,
  size = 84,
}) => {
  const pulse = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, {
          toValue: 1,
          duration: 1800,
          useNativeDriver: true,
        }),
        Animated.timing(pulse, {
          toValue: 0,
          duration: 1800,
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [pulse]);

  const outerPulseScale = pulse.interpolate({
    inputRange: [0, 1],
    outputRange: [1, 1.05],
  });

  // Concentric ring diameters matching reference proportions
  const innerCircleSize = size;
  const ring1Size = 124;
  const ring2Size = 164;
  const ring3Size = 206;

  return (
    <TouchableOpacity
      activeOpacity={0.85}
      onPress={onPress}
      style={[styles.wrapper, {width: ring3Size, height: ring3Size}]}>
      {/* Outer faint peach ring */}
      <Animated.View
        style={[
          styles.ring,
          {
            width: ring3Size,
            height: ring3Size,
            borderRadius: ring3Size / 2,
            backgroundColor: '#FFF0EB',
            transform: [{scale: outerPulseScale}],
          },
        ]}
      />
      {/* Middle soft peach ring */}
      <View
        style={[
          styles.ring,
          {
            width: ring2Size,
            height: ring2Size,
            borderRadius: ring2Size / 2,
            backgroundColor: '#FDE4DC',
          },
        ]}
      />
      {/* Inner soft peach ring */}
      <View
        style={[
          styles.ring,
          {
            width: ring1Size,
            height: ring1Size,
            borderRadius: ring1Size / 2,
            backgroundColor: '#FCD2C5',
          },
        ]}
      />
      {/* Core red-orange mic button */}
      <View
        style={[
          styles.micCircle,
          {
            width: innerCircleSize,
            height: innerCircleSize,
            borderRadius: innerCircleSize / 2,
          },
        ]}>
        <Icon name="mic" size={40} color={colors.white} />
      </View>
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  wrapper: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  ring: {
    position: 'absolute',
  },
  micCircle: {
    position: 'absolute',
    backgroundColor: colors.red,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: colors.red,
    shadowOffset: {width: 0, height: 6},
    shadowOpacity: 0.35,
    shadowRadius: 10,
    elevation: 8,
  },
});

export default MicrophoneButton;
