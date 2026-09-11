import {TextStyle, Platform} from 'react-native';
import {colors} from './colors';

const fontFamily = Platform.select({
  android: 'sans-serif',
  ios: 'System',
  default: 'sans-serif',
});

/**
 * Centralized typography tokens for the Voice Assistant UI.
 */
export const typography: Record<string, TextStyle> = {
  heading: {
    fontFamily,
    fontSize: 32,
    fontWeight: '800',
    lineHeight: 38,
    color: colors.textPrimary,
  },
  subtitle: {
    fontFamily,
    fontSize: 16,
    fontWeight: '400',
    lineHeight: 22,
    color: colors.textSecondary,
  },
  body: {
    fontFamily,
    fontSize: 15,
    fontWeight: '500',
    lineHeight: 20,
    color: colors.textPrimary,
  },
  button: {
    fontFamily,
    fontSize: 17,
    fontWeight: '700',
    color: colors.textOnDark,
  },
  navigation: {
    fontFamily,
    fontSize: 11,
    fontWeight: '600',
    color: colors.navInactive,
  },
  cardTitle: {
    fontFamily,
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  cardSubtitle: {
    fontFamily,
    fontSize: 13,
    fontWeight: '400',
    color: colors.textSecondary,
  },
};

