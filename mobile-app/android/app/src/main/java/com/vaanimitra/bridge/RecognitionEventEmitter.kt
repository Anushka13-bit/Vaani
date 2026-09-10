package com.vaanimitra.bridge

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.vaanimitra.stt.TranscriptSegment
import android.util.Log

/**
 * RecognitionEventEmitter — streams transcript/confidence and wake-word events from native → RN.
 *
 * Events emitted:
 *   onTranscriptSegment      — Whisper segment from VoicePipeline (text, confidence, startMs, endMs)
 *   onConfirmationRequired   — High-stakes intent needs user confirmation
 *   onWakeWordDetected       — Wake word fired (model name + score)
 *   onWakeWordError          — Service stopped due to an error (reason string)
 */
@ReactModule(name = RecognitionEventEmitter.NAME)
class RecognitionEventEmitter(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "RecognitionEventEmitter"
        const val EVENT_TRANSCRIPT_SEGMENT    = "onTranscriptSegment"
        const val EVENT_CONFIRMATION_REQUIRED = "onConfirmationRequired"
        const val EVENT_WAKE_WORD_DETECTED    = "onWakeWordDetected"
        const val EVENT_WAKE_WORD_ERROR       = "onWakeWordError"
        private const val TAG = "RecognitionEventEmitter"

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

    // ── Transcript ────────────────────────────────────────────────────────────

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

    // ── Wake word ─────────────────────────────────────────────────────────────

    /**
     * Fired when the wake word engine triggers (before mic is handed to VoicePipeline).
     * @param modelName  e.g. "Hey Lily" or "Hey Jarvis"
     * @param score      Raw detection confidence from openWakeWord (0.0–1.0)
     */
    fun emitWakeWordDetected(modelName: String, score: Float) {
        if (!reactContext.hasActiveReactInstance()) return
        val params = Arguments.createMap().apply {
            putString("model", modelName)
            putDouble("score", score.toDouble())
        }
        emit(EVENT_WAKE_WORD_DETECTED, params)
        Log.i(TAG, "emitWakeWordDetected: model=$modelName score=$score")
    }

    /**
     * Fired when the wake-word foreground service self-terminates due to an error
     * (e.g. missing ONNX model assets, AudioRecord init failure).
     * @param reason  Human-readable description of why the service stopped.
     */
    fun emitWakeWordError(reason: String) {
        if (!reactContext.hasActiveReactInstance()) return
        val params = Arguments.createMap().apply {
            putString("reason", reason)
        }
        emit(EVENT_WAKE_WORD_ERROR, params)
        Log.e(TAG, "emitWakeWordError: $reason")
    }

    // ── Internal emit helper ──────────────────────────────────────────────────

    private fun emit(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    // ── Required for NativeEventEmitter on RN side ────────────────────────────

    @ReactMethod
    fun addListener(@Suppress("UNUSED_PARAMETER") eventName: String) {
        // Required boilerplate for RN NativeEventEmitter — no-op
    }

    @ReactMethod
    fun removeListeners(@Suppress("UNUSED_PARAMETER") count: Int) {
        // Required boilerplate for RN NativeEventEmitter — no-op
    }
}
