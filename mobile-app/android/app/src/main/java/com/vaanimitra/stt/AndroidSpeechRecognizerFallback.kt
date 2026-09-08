package com.vaanimitra.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uses Google’s on-device speech recognizer when the ONNX Whisper model is not bundled.
 * Explicitly targets Google’s recognition service to avoid routing back to VaaniMitra itself.
 */
object AndroidSpeechRecognizerFallback {

    private const val TAG = "AndroidSttFallback"
    private const val TIMEOUT_MS = 12_000L

    private val GOOGLE_RECOGNITION_COMPONENTS = listOf(
        ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        ),
        ComponentName(
            "com.google.android.tts",
            "com.google.android.apps.speechservices.googletts.service.GoogleTTSRecognitionService",
        ),
    )

    suspend fun recognize(context: Context): String = suspendCancellableCoroutine { cont ->
        val appContext = context.applicationContext
        val recognizer = createExternalRecognizer(appContext)
            ?: run {
                cont.resumeWithException(
                    IllegalStateException(
                        "No external speech recognizer available. Install Google app or enable speech services.",
                    ),
                )
                return@suspendCancellableCoroutine
            }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                recognizer.destroy()
                if (cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("Speech recognition error code=$error"),
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                recognizer.destroy()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                Log.i(TAG, "Recognized: '${text.take(60)}'")
                if (cont.isActive) cont.resume(text)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)

        cont.invokeOnCancellation {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun createExternalRecognizer(context: Context): SpeechRecognizer? {
        for (component in GOOGLE_RECOGNITION_COMPONENTS) {
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
                if (recognizer != null) {
                    Log.i(TAG, "Using recognizer: ${component.flattenToShortString()}")
                    return recognizer
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recognizer unavailable: ${component.flattenToShortString()} — ${e.message}")
            }
        }
        return null
    }
}
