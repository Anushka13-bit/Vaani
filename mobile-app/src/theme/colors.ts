/**
 * Centralized color tokens for the Voice Assistant UI.
 * Do not use raw hex values outside this file.
 */
export const colors = {
  // Backgrounds
  cream: '#FFF9F2',
  creamLight: '#FDF6EA',
  screenBackground: '#F8F9FA',
  navy: '#111827',
  navyDark: '#0F172A',

  // Accents
  orange: '#F5A03C',
  orangeLight: '#F8B65E',
  orangeDark: '#E8871F',
  orangeDeep: '#D97324',
  red: '#FF4D4D',
  redLight: '#FF6B6B',
  peach: '#F9DDBB',
  peachLight: '#FCE9D2',

  // Neutrals
  white: '#FFFFFF',
  cardBackground: '#FFFFFF',
  cardBackgroundAlt: '#F9FAFB',
  cardBorder: '#F3F4F6',
  privacyCardBackground: '#FFE2B3',

  // Text
  textPrimary: '#111827',
  textSecondary: '#6B7280',
  textOnDark: '#FFFFFF',
  textMuted: '#9CA3AF',

  // Icon circles
  iconCircleLight: '#FDEDE3',
  iconCircleRed: '#FF4D4D',
  iconCircleOrange: '#FF6B4A',
  iconCircleBlue: '#1E88E5',
  iconCircleGreen: '#43A047',
  iconCirclePurple: '#5C6BC0',
  iconCircleGrey: '#757575',

  // Bottom nav
  navActive: '#FF4D4D',
  navInactive: '#9CA3AF',
  navBackground: '#FFFFFF',
  navBorder: '#E5E7EB',
} as const;

export type ColorToken = keyof typeof colors;

