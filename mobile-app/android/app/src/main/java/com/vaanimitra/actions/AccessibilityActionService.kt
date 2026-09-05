package com.vaanimitra.actions

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityActionService — extends android.accessibilityservice.AccessibilityService.
 * Used as a fallback when standard Android Intents can't reach a target app's UI fields.
 *
 * Must be declared in AndroidManifest.xml:
 *   <service android:name=".actions.AccessibilityActionService"
 *            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *       <intent-filter>
 *           <action android:name="android.accessibilityservice.AccessibilityService"/>
 *       </intent-filter>
 *       <meta-data android:name="android.accessibilityservice"
 *                  android:resource="@xml/accessibility_service_config"/>
 *   </service>
 *
 * Config: res/xml/accessibility_service_config.xml
 *
 * NOTE from spec §7: AccessibilityService only reads/acts on UI view hierarchy.
 * It does NOT access audio, camera, or screen pixels. It is purely for filling
 * text fields and tapping UI elements in third-party apps when standard Intents
 * are insufficient.
 */
class AccessibilityActionService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityActionSvc"

        // Singleton reference so ActionExecutor can dispatch to this service
        @Volatile
        var instance: AccessibilityActionService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AccessibilityActionService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AccessibilityActionService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO: handle events for context-aware action dispatching
        // e.g. detect when a text field becomes focused, then fill it with dictated text
        Log.v(TAG, "onAccessibilityEvent: ${event?.eventType}")
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    /**
     * Fills the currently focused editable field with [text].
     * Equivalent to pasting text into a text input in the foreground app.
     */
    fun fillFocusedField(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null — service may not be connected")
            return false
        }
        // Find editable focused node
        val focused = findFirstFocusedEditText(rootNode)
        return if (focused != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            val result = focused.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                args,
            )
            Log.i(TAG, "fillFocusedField result=$result text=${text.take(40)}")
            result
        } else {
            Log.w(TAG, "No focused editable field found")
            false
        }
    }

    private fun findFirstFocusedEditText(
        node: android.view.accessibility.AccessibilityNodeInfo?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val found = findFirstFocusedEditText(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
