package com.vaanimitra.recognition

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.vaanimitra.VaaniMitraComponents
import com.vaanimitra.audio.AudioCaptureManager
import com.vaanimitra.audio.VoiceActivityDetector
import com.vaanimitra.nlu.ActionType
import com.vaanimitra.stt.AndroidSpeechRecognizerFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PersonalizedRecognitionService — system-wide STT + voice command dispatch.
 */
class PersonalizedRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "PersonalizedRecogSvc"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var captureManager: AudioCaptureManager
    private lateinit var vad: VoiceActivityDetector

    override fun onCreate() {
        super.onCreate()
        captureManager = AudioCaptureManager()
        vad = VoiceActivityDetector()

        val adapterManager = VaaniMitraComponents.adapterManager(applicationContext)
        val engine = VaaniMitraComponents.whisperEngine(applicationContext)
        val restored = adapterManager.restorePersistedStack()
        restored.firstOrNull()?.let { handle ->
            engine.activeAdapterId = handle.adapterId
            engine.activeAdapterPath = handle.filePath
        }

        Log.i(TAG, "PersonalizedRecognitionService created — restored ${restored.size} adapter(s)")
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "onStartListening")
        listener?.readyForSpeech(Bundle())

        scope.launch {
            try {
                val engine = VaaniMitraComponents.whisperEngine(applicationContext)
                val adapterManager = VaaniMitraComponents.adapterManager(applicationContext)
                val phrasebookMatcher = VaaniMitraComponents.phrasebookMatcher()
                val intentParser = VaaniMitraComponents.intentParser()
                val actionExecutor = VaaniMitraComponents.actionExecutor(applicationContext)

                if (adapterManager.currentStackedAdapters().isEmpty()) {
                    adapterManager.restorePersistedStack()
                }
                adapterManager.currentStackedAdapters().firstOrNull()?.let { handle ->
                    engine.activeAdapterId = handle.adapterId
                    engine.activeAdapterPath = handle.filePath
                }

                val transcript = withContext(Dispatchers.IO) {
                    if (engine.isOnnxModelAvailable()) {
                        listener?.beginningOfSpeech()
                        val rawPcm = captureManager.captureSeconds(8)
                        val speechPcm = vad.trimSilence(rawPcm)
                        engine.transcribe(speechPcm).text
                    } else {
                        listener?.beginningOfSpeech()
                        AndroidSpeechRecognizerFallback.recognize(applicationContext)
                    }
                }

                if (transcript.isBlank()) {
                    listener?.error(SpeechRecognizer.ERROR_NO_MATCH)
                    return@launch
                }

                Log.i(TAG, "Transcript: '$transcript'")

                val phrasebookMatch = phrasebookMatcher.match(transcript)
                val parsedIntent = if (phrasebookMatch.matched && phrasebookMatch.intent != null) {
                    phrasebookMatch.intent!!
                } else {
                    intentParser.parse(transcript)
                }

                Log.i(TAG, "Parsed intent: ${parsedIntent.action} entities=${parsedIntent.entities}")

                when (parsedIntent.action) {
                    ActionType.DICTATE_TEXT -> {
                        val text = parsedIntent.entities["text"] ?: transcript
                        val results = Bundle().apply {
                            putStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION,
                                arrayListOf(text),
                            )
                            putFloatArray(
                                SpeechRecognizer.CONFIDENCE_SCORES,
                                floatArrayOf(0.85f),
                            )
                        }
                        listener?.results(results)
                    }
                    else -> {
                        val actionResult = withContext(Dispatchers.IO) {
                            actionExecutor.execute(parsedIntent)
                        }
                        val results = Bundle().apply {
                            putStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION,
                                arrayListOf(actionResult.message),
                            )
                            putFloatArray(
                                SpeechRecognizer.CONFIDENCE_SCORES,
                                floatArrayOf(parsedIntent.confidence),
                            )
                        }
                        listener?.results(results)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recognition error: ${e.message}", e)
                listener?.error(SpeechRecognizer.ERROR_SERVER)
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        captureManager.stopStreaming()
        Log.d(TAG, "onStopListening")
    }

    override fun onCancel(listener: Callback?) {
        captureManager.stopStreaming()
        Log.d(TAG, "onCancel")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "PersonalizedRecognitionService destroyed")
    }
}
