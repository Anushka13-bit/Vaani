import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import Icon from 'react-native-vector-icons/MaterialIcons';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import type {RootStackParamList} from '../navigation/types';
import {colors} from '../theme/colors';
import {LocalDb} from '../storage/localDb';
import DecorativeBackground from '../components/DecorativeBackground';
import FeatureItem from '../components/FeatureItem';

type Props = NativeStackScreenProps<RootStackParamList, 'Welcome'>;

/**
 * Top-right circular microphone badge with 4 radiating orange sound rays.
 */
const MicrophoneBadge: React.FC = () => {
  return (
    <View style={styles.badgeContainer}>
      {/* 4 Radiating sound rays outside top-right */}
      <View style={[styles.ray, styles.ray1]} />
      <View style={[styles.ray, styles.ray2]} />
      <View style={[styles.ray, styles.ray3]} />
      <View style={[styles.ray, styles.ray4]} />

      {/* Main Orange Circle */}
      <View style={styles.badgeCircle}>
        <Icon name="mic" size={30} color="#111827" />
      </View>
    </View>
  );
};

const WelcomeScreen: React.FC<Props> = ({navigation}) => {
  return (
    <View style={styles.root}>
      <DecorativeBackground />
      <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
        <View style={styles.content}>
          {/* Header area with heading, subtitle, and top-right badge */}
          <View style={styles.headerArea}>
            <View style={styles.textColumn}>
              <Text style={styles.heading}>
                Your Voice.{'\n'}More Possibilities.
              </Text>
              <Text style={styles.subtitle}>
                Speak naturally. We&apos;ll take{'\n'}care of the rest.
              </Text>
            </View>

            <View style={styles.badgeWrapper}>
              <MicrophoneBadge />
            </View>
          </View>

          {/* Three Feature Rows */}
          <View style={styles.features}>
            <FeatureItem
              type="mic"
              label={'Do everyday tasks\nwith your voice'}
            />
            <FeatureItem
              type="appGrid"
              label="Works across all apps"
            />
            <FeatureItem
              type="user"
              label="Personalised for you"
            />
          </View>

          {/* Bottom Get Started Pill Button */}
          <TouchableOpacity
            style={[styles.button, styles.buttonPush]}
            activeOpacity={0.85}
            onPress={async () => {
              await LocalDb.setOnboardingCompleted();
              navigation.replace('Listening');
            }}>
            <Text style={styles.buttonText}>Get Started</Text>
            <Icon
              name="arrow-forward"
              size={20}
              color={colors.white}
              style={styles.buttonIcon}
            />
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    </View>
  );
};

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.cream,
  },
  safeArea: {
    flex: 1,
  },
  content: {
    flex: 1,
    paddingHorizontal: 24,
    paddingTop: 16,
  },
  headerArea: {
    position: 'relative',
    marginTop: 8,
  },
  textColumn: {
    maxWidth: '78%',
  },
  heading: {
    fontSize: 32,
    fontWeight: '800',
    color: colors.textPrimary,
    lineHeight: 38,
    letterSpacing: -0.5,
  },
  subtitle: {
    fontSize: 16,
    fontWeight: '400',
    color: colors.textSecondary,
    lineHeight: 22,
    marginTop: 10,
  },
  badgeWrapper: {
    position: 'absolute',
    right: -4,
    bottom: -16,
  },
  badgeContainer: {
    width: 80,
    height: 80,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeCircle: {
    width: 58,
    height: 58,
    borderRadius: 29,
    backgroundColor: '#FFA726',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#FFA726',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.35,
    shadowRadius: 6,
    elevation: 4,
  },
  ray: {
    position: 'absolute',
    width: 8,
    height: 2.5,
    backgroundColor: '#FFA726',
    borderRadius: 1.5,
  },
  ray1: {
    top: 10,
    right: 18,
    transform: [{rotate: '-55deg'}],
  },
  ray2: {
    top: 16,
    right: 8,
    transform: [{rotate: '-25deg'}],
  },
  ray3: {
    top: 28,
    right: 4,
    transform: [{rotate: '5deg'}],
  },
  ray4: {
    top: 40,
    right: 8,
    transform: [{rotate: '35deg'}],
  },
  spacer: {
    flex: 0,
  },
  features: {
    marginTop: '18%',
    marginBottom: 28,
  },
  buttonPush: {
    marginTop: 'auto',
  },
  button: {
    height: 54,
    borderRadius: 27,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.navy,
    marginBottom: 16,
    shadowColor: '#000000',
    shadowOffset: {width: 0, height: 4},
    shadowOpacity: 0.15,
    shadowRadius: 8,
    elevation: 4,
  },
  buttonText: {
    fontSize: 17,
    fontWeight: '700',
    color: colors.white,
  },
  buttonIcon: {
    marginLeft: 8,
  },
});

export default WelcomeScreen;
