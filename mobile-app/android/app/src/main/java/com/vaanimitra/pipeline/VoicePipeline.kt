package com.vaanimitra.pipeline

import android.content.Context
import android.util.Log
import com.vaanimitra.VaaniMitraComponents
import com.vaanimitra.audio.AudioCaptureManager
import com.vaanimitra.audio.VoiceActivityDetector
import com.vaanimitra.nlu.ActionType
import com.vaanimitra.stt.AndroidSpeechRecognizerFallback
import com.vaanimitra.stt.ConfidenceScorer
import com.vaanimitra.stt.ModelBundleManager
import com.vaanimitra.stt.TranscriptSegment
import com.vaanimitra.ui.ClarificationActivity as ClarificationUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * End-to-end: VAD capture → TORGO Whisper ONNX → confidence → phrasebook/NLU → gated action.
 */
object VoicePipeline {

    private const val TAG = "VoicePipeline"
    private const val LISTEN_TIMEOUT_MS = 8000L
    private const val SILENCE_END_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun runCommandSession(context: Context, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                processSession(context)
            } catch (e: Exception) {
                Log.e(TAG, "Session failed: ${e.message}", e)
            } finally {
                onComplete()
            }
        }
    }

    private suspend fun processSession(context: Context) {
        val app = context.applicationContext
        val capture = AudioCaptureManager()
        val vad = VoiceActivityDetector()
        val engine = VaaniMitraComponents.whisperEngine(app)
        val adapterManager = VaaniMitraComponents.adapterManager(app)
        val scorer = ConfidenceScorer()
        val phrasebook = VaaniMitraComponents.phrasebookMatcher()
        val parser = VaaniMitraComponents.intentParser()
        val executor = VaaniMitraComponents.actionExecutor(app)

        adapterManager.restorePersistedStack()
        val activeAdapter = adapterManager.currentStackedAdapters().firstOrNull()
        val adapterId = activeAdapter?.adapterId ?: "torgo_cluster_english_v1"

        // VAD-gated capture with timeout (not fixed 8s hot mic forever)
        val pcm = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
            captureWithVad(capture, vad)
        } ?: run {
            Log.w(TAG, "Listen timeout — no speech")
            return
        }

        if (pcm.isEmpty()) return

        val (transcript, avgLogProb, ep) = withContext(Dispatchers.Default) {
            if (ModelBundleManager.isBundleReady(app, adapterId)) {
                val result = engine.transcribe(pcm)
                val seg = result.segments.firstOrNull()
                Triple(result.text, seg?.confidence ?: 0.5f, engine.executionProvider)
            } else {
                Log.w(TAG, "ONNX bundle missing — Google STT fallback")
                val text = AndroidSpeechRecognizerFallback.recognize(app)
                Triple(text, 0.7f, "GoogleSTT")
            }
        }

        if (transcript.isBlank()) return
        Log.i(TAG, "Transcript ($ep): $transcript")

        val segment = TranscriptSegment(transcript, 0, pcm.size * 1000L / 16000, avgLogProb)
        val scored = scorer.score(listOf(segment), if (avgLogProb > 0) listOf(avgLogProb) else null)
        val finalSeg = scored.first()

        var finalText = transcript
        if (scorer.isLowConfidence(finalSeg)) {
            finalText = ClarificationUi.requestChoice(
                app,
                "Did you mean?",
                listOf(transcript, "(cancel)"),
            ) ?: return
            if (finalText.contains("cancel")) return
        }

        val phraseMatch = phrasebook.match(finalText)
        val intent = if (phraseMatch.matched && phraseMatch.intent != null) {
            phraseMatch.intent!!
        } else {
            parser.parse(finalText)
        }

        if (intent.action == ActionType.DICTATE_TEXT) return

        val confirmed = ConfirmationGate.confirmIfNeeded(app, intent)
        if (!confirmed) {
            Log.i(TAG, "Action cancelled by user")
            return
        }

        withContext(Dispatchers.IO) {
            executor.execute(intent)
        }
    }

    /** Stream until silence or max duration. */
    private suspend fun captureWithVad(
        capture: AudioCaptureManager,
        vad: VoiceActivityDetector,
    ): ShortArray = withContext(Dispatchers.IO) {
        val maxSamples = AudioCaptureManager.SAMPLE_RATE * 8
        val buffer = mutableListOf<Short>()
        var lastSpeech = System.currentTimeMillis()
        val start = System.currentTimeMillis()

        capture.streamPcm { chunk ->
            buffer.addAll(chunk.toList())
            val frame = if (chunk.size >= 480) chunk.copyOfRange(0, 480) else chunk
            if (vad.isSpeech(frame)) lastSpeech = System.currentTimeMillis()
            if (System.currentTimeMillis() - lastSpeech > SILENCE_END_MS && buffer.size > 1600) {
                capture.stopStreaming()
            }
            if (buffer.size >= maxSamples) capture.stopStreaming()
            if (System.currentTimeMillis() - start > LISTEN_TIMEOUT_MS) capture.stopStreaming()
        }

        val arr = buffer.toShortArray()
        vad.trimSilence(arr)
    }
}
