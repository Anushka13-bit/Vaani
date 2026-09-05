package com.vaanimitra.bridge

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.vaanimitra.stt.TranscriptSegment
import android.util.Log

/**
 * RecognitionEventEmitter — streams transcript/confidence events from native → RN (§2.2).
 *
 * PersonalizedRecognitionService calls emitTranscriptSegment() as segments arrive.
 * The RN layer subscribes via SpeechBridge.onTranscriptSegment(callback).
 *
 * This implements the ReactContextBaseJavaModule pattern (not RCTEventEmitter)
 * so it can be included in SpeechModulePackage alongside SpeechModule.
 */
@ReactModule(name = RecognitionEventEmitter.NAME)
class RecognitionEventEmitter(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "RecognitionEventEmitter"
        const val EVENT_TRANSCRIPT_SEGMENT = "onTranscriptSegment"
        const val EVENT_CONFIRMATION_REQUIRED = "onConfirmationRequired"
        private const val TAG = "RecognitionEventEmitter"

        // Singleton so PersonalizedRecognitionService can call emit directly
        @Volatile
        var instance: RecognitionEventEmitter? = null
    }

    override fun getName(): String = NAME

    override fun initialize() {
        super.initialize()
        instance = this
        Log.i(TAG, "RecognitionEventEmitter initialized")
    }

    override fun invalidate() {
        instance = null
        super.invalidate()
    }

    // ── Emit helpers ──────────────────────────────────────────────────────────

    fun emitTranscriptSegment(segment: TranscriptSegment) {
        if (!reactContext.hasActiveReactInstance()) return
        val params = Arguments.createMap().apply {
            putString("text", segment.text)
            putDouble("confidence", segment.confidence.toDouble())
            putDouble("startMs", segment.startMs.toDouble())
            putDouble("endMs", segment.endMs.toDouble())
        }
        emit(EVENT_TRANSCRIPT_SEGMENT, params)
        Log.v(TAG, "emitTranscriptSegment: '${segment.text.take(30)}' conf=${segment.confidence}")
    }

    fun emitConfirmationRequired(confirmationText: String, intentJson: String) {
        if (!reactContext.hasActiveReactInstance()) return
        val params = Arguments.createMap().apply {
            putString("text", confirmationText)
            putString("intentJson", intentJson)
        }
        emit(EVENT_CONFIRMATION_REQUIRED, params)
    }

    private fun emit(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    // ── Required for NativeEventEmitter on RN side ────────────────────────────

    @ReactMethod
    fun addListener(@Suppress("UNUSED_PARAMETER") eventName: String) {
        // Required for RN NativeEventEmitter — no-op
    }

    @ReactMethod
    fun removeListeners(@Suppress("UNUSED_PARAMETER") count: Int) {
        // Required for RN NativeEventEmitter — no-op
    }
}
