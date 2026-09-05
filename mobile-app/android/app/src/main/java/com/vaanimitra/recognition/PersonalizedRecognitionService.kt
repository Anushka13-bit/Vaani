package com.vaanimitra.recognition

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.vaanimitra.audio.AudioCaptureManager
import com.vaanimitra.audio.VoiceActivityDetector
import com.vaanimitra.stt.AdapterManager
import com.vaanimitra.stt.ConfidenceScorer
import com.vaanimitra.stt.TranscriptionResult
import com.vaanimitra.stt.WhisperInferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * PersonalizedRecognitionService — extends android.speech.RecognitionService.
 * Registered in AndroidManifest.xml as the custom STT provider.
 *
 * This is the system-wide dictation path (§3.2). It does NOT go through the
 * React Native layer at runtime — Android routes STT requests directly here.
 *
 * Must be declared in AndroidManifest.xml:
 *   <service android:name=".recognition.PersonalizedRecognitionService"
 *            android:permission="android.permission.BIND_RECOGNITION_SERVICE">
 *       <intent-filter>
 *           <action android:name="android.speech.RecognitionService"/>
 *       </intent-filter>
 *   </service>
 */
class PersonalizedRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "PersonalizedRecogSvc"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var engine: WhisperInferenceEngine
    private lateinit var adapterManager: AdapterManager
    private lateinit var captureManager: AudioCaptureManager
    private lateinit var vad: VoiceActivityDetector
    private lateinit var scorer: ConfidenceScorer

    override fun onCreate() {
        super.onCreate()
        engine = WhisperInferenceEngine(applicationContext)
        adapterManager = AdapterManager(applicationContext)
        captureManager = AudioCaptureManager()
        vad = VoiceActivityDetector()
        scorer = ConfidenceScorer()
        Log.i(TAG, "PersonalizedRecognitionService created")
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "onStartListening")
        listener?.readyForSpeech(Bundle())

        scope.launch(Dispatchers.IO) {
            try {
                // Capture audio
                val rawPcm = captureManager.captureSeconds(8)

                // VAD — trim silence
                val speechPcm = vad.trimSilence(rawPcm)
                listener?.beginningOfSpeech()

                // Transcribe
                val result: TranscriptionResult = engine.transcribe(speechPcm)

                // Score confidence
                val scoredSegments = scorer.score(result.segments)
                val hasLowConfidence = scoredSegments.any { scorer.isLowConfidence(it) }

                // Build results bundle
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(result.text),
                    )
                    putFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                        floatArrayOf(scoredSegments.firstOrNull()?.confidence ?: 0.8f),
                    )
                }

                if (hasLowConfidence) {
                    // Emit partial result — RN overlay or native overlay shows clarification
                    listener?.partialResults(results)
                    Log.d(TAG, "Low confidence segment — emitting partial result")
                } else {
                    listener?.results(results)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription error: ${e.message}", e)
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
