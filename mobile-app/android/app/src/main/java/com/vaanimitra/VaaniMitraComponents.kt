package com.vaanimitra

import android.content.Context
import com.vaanimitra.actions.ActionExecutor
import com.vaanimitra.actions.AndroidIntentActions
import com.vaanimitra.nlu.IntentParser
import com.vaanimitra.nlu.PhrasebookMatcher
import com.vaanimitra.stt.AdapterManager
import com.vaanimitra.stt.WhisperInferenceEngine

/**
 * Shared singletons used by SpeechModule, PersonalizedRecognitionService, and AccessibilityModule.
 */
object VaaniMitraComponents {

    @Volatile
    private var adapterManager: AdapterManager? = null

    @Volatile
    private var whisperEngine: WhisperInferenceEngine? = null

    @Volatile
    private var phrasebookMatcher: PhrasebookMatcher? = null

    @Volatile
    private var intentParser: IntentParser? = null

    @Volatile
    private var actionExecutor: ActionExecutor? = null

    fun adapterManager(context: Context): AdapterManager {
        val appContext = context.applicationContext
        return adapterManager ?: synchronized(this) {
            adapterManager ?: AdapterManager(appContext).also { adapterManager = it }
        }
    }

    fun whisperEngine(context: Context): WhisperInferenceEngine {
        val appContext = context.applicationContext
        return whisperEngine ?: synchronized(this) {
            whisperEngine ?: WhisperInferenceEngine(appContext).also { whisperEngine = it }
        }
    }

    fun phrasebookMatcher(): PhrasebookMatcher {
        return phrasebookMatcher ?: synchronized(this) {
            phrasebookMatcher ?: PhrasebookMatcher().also { phrasebookMatcher = it }
        }
    }

    fun intentParser(): IntentParser {
        return intentParser ?: synchronized(this) {
            intentParser ?: IntentParser().also { intentParser = it }
        }
    }

    @Volatile
    private var audioCaptureManager: com.vaanimitra.audio.AudioCaptureManager? = null

    fun audioCaptureManager(): com.vaanimitra.audio.AudioCaptureManager {
        return audioCaptureManager ?: synchronized(this) {
            audioCaptureManager ?: com.vaanimitra.audio.AudioCaptureManager().also { audioCaptureManager = it }
        }
    }

    fun actionExecutor(context: Context): ActionExecutor {
        val appContext = context.applicationContext
        return actionExecutor ?: synchronized(this) {
            actionExecutor ?: ActionExecutor(
                appContext,
                AndroidIntentActions(appContext),
            ).also { actionExecutor = it }
        }
    }
}
