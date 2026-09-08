package com.vaanimitra.bridge

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.vaanimitra.VaaniMitraComponents
import com.vaanimitra.actions.AccessibilityActionService
import com.vaanimitra.nlu.ActionType
import com.vaanimitra.nlu.ParsedIntent
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@ReactModule(name = AccessibilityModule.NAME)
class AccessibilityModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "AccessibilityModule"
        private const val TAG = "AccessibilityModule"
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun getName(): String = NAME

    @ReactMethod
    fun isAccessibilityServiceEnabled(promise: Promise) {
        try {
            val enabledServices = Settings.Secure.getString(
                reactContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val enabled = enabledServices.contains("vaanimitra")
                || AccessibilityActionService.instance != null
            promise.resolve(enabled)
        } catch (e: Exception) {
            promise.reject("ACCESSIBILITY_CHECK_FAILED", e.message)
        }
    }

    @ReactMethod
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            reactContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
        }
    }

    @ReactMethod
    fun executeParsedIntent(intentJson: String, promise: Promise) {
        try {
            val json = JSONObject(intentJson)
            val actionStr = json.optString("action", "UNKNOWN")
            val action = try {
                ActionType.valueOf(actionStr)
            } catch (_: Exception) {
                ActionType.UNKNOWN
            }

            val entitiesJson = json.optJSONObject("entities")
            val entities = mutableMapOf<String, String>()
            entitiesJson?.keys()?.forEach { k -> entities[k] = entitiesJson.getString(k) }

            val intent = ParsedIntent(
                action = action,
                entities = entities,
                confidence = json.optDouble("confidence", 0.5).toFloat(),
                requiresConfirmation = json.optBoolean("requiresConfirmation", false),
            )

            scope.launch(Dispatchers.IO) {
                try {
                    if (intent.action == ActionType.DICTATE_TEXT) {
                        val service = AccessibilityActionService.instance
                        val text = entities["text"] ?: ""
                        if (service != null) {
                            val result = service.fillFocusedField(text)
                            val map = Arguments.createMap().apply {
                                putBoolean("success", result)
                                putString("message", if (result) "Field filled" else "Field not found")
                                putBoolean("requiresAccessibilityFallback", false)
                            }
                            promise.resolve(map)
                        } else {
                            promise.reject(
                                "ACCESSIBILITY_NOT_ENABLED",
                                "Enable VaaniMitra Accessibility to dictate into other apps",
                            )
                        }
                        return@launch
                    }

                    val actionResult = VaaniMitraComponents
                        .actionExecutor(reactContext)
                        .execute(intent)

                    val map = Arguments.createMap().apply {
                        putBoolean("success", actionResult.success)
                        putString("message", actionResult.message)
                        putBoolean("requiresAccessibilityFallback", actionResult.requiresAccessibilityFallback)
                    }
                    promise.resolve(map)
                } catch (e: Exception) {
                    promise.reject("INTENT_EXECUTION_FAILED", e.message, e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeParsedIntent failed: ${e.message}")
            promise.reject("INTENT_EXECUTION_FAILED", e.message, e)
        }
    }
}
