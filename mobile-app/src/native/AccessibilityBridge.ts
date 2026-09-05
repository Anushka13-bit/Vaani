/**
 * VaaniMitra — AccessibilityBridge (§2.2)
 * Typed RN-side wrapper around the native AccessibilityModule.
 *
 * Native side: android/.../bridge/AccessibilityModule.kt
 */
import { NativeModules } from 'react-native';
import type { ActionResult, ParsedIntent } from './types';

const { AccessibilityModule } = NativeModules;

if (!AccessibilityModule) {
  console.warn(
    '[AccessibilityBridge] AccessibilityModule native module not found. ' +
    'Ensure the native Android build includes the module and you ran react-native run-android.',
  );
}

export const AccessibilityBridge = {
  /**
   * Returns true if AccessibilityActionService is enabled in system settings.
   */
  isAccessibilityServiceEnabled: (): Promise<boolean> =>
    AccessibilityModule.isAccessibilityServiceEnabled(),

  /**
   * Deep-links to Android Accessibility Settings so the user can enable the service.
   */
  openAccessibilitySettings: (): void =>
    AccessibilityModule.openAccessibilitySettings(),

  /**
   * Execute a parsed intent from the RN side (after user confirmation tap).
   * The intent is serialized to JSON and dispatched by the native ActionExecutor.
   */
  executeParsedIntent: (intent: ParsedIntent): Promise<ActionResult> =>
    AccessibilityModule.executeParsedIntent(JSON.stringify(intent)),
};
