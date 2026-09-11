/**
 * Centralized spacing scale for the Voice Assistant UI.
 */
export const spacing = {
  xs: 4,
  sm: 8,
  cardGap: 10,
  md: 16,
  lg: 24,
  xl: 32,
} as const;

export type SpacingToken = keyof typeof spacing;

