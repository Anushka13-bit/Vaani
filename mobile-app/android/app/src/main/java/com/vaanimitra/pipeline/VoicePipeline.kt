package com.vaanimitra.pipeline

import android.content.Context
import android.util.Log
import com.vaanimitra.VaaniMitraComponents
import com.vaanimitra.actions.ActionResult
import com.vaanimitra.audio.AudioCaptureManager
import com.vaanimitra.audio.VoiceActivityDetector
import com.vaanimitra.bridge.RecognitionEventEmitter
import com.vaanimitra.nlu.ActionType
import com.vaanimitra.nlu.ParsedIntent
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
 * What happened to a transcript after phrasebook/NLU routing — the same shape whether
 * it arrived via wake-word capture ([VoicePipeline.processSession]) or a manual
 * "tap mic" capture ([VoicePipeline.actOnTranscript] called directly). A raw transcript
 * string alone tells a caller nothing about whether an action actually ran.
 */
sealed class TranscriptOutcome {
    /** No action keyword matched — transcript is plain dictation. */
    data class Dictated(val text: String) : TranscriptOutcome()
    /** Matched an action and ActionExecutor ran it (success or failure is in [result]). */
    data class ActionExecuted(val intent: ParsedIntent, val result: ActionResult) : TranscriptOutcome()
    /** User declined or the confirmation prompt timed out. */
    data class Cancelled(val reason: String) : TranscriptOutcome()
    /** Blank transcript, a non-speech marker, or a dismissed low-confidence clarification. */
    data class Ignored(val reason: String) : TranscriptOutcome()
}

/**
 * End-to-end: VAD capture → TORGO Whisper ONNX → confidence → phrasebook/NLU → gated action.
 *
 * Mic contention contract:
 *   Caller (WakeWordForegroundService) MUST have stopped the wake-word engine before calling
 *   runCommandSession(). This function opens AudioRecord, does its work, then calls onComplete()
 *   which is the caller's signal to restart the wake engine. onComplete() is guaranteed to be
 *   called regardless of how the session exits (timeout, silence, error).
 *
 * DICTATE_TEXT:
 *   If the recognized utterance matches no action keyword, the transcript is emitted to React
 *   Native via RecognitionEventEmitter.emitTranscriptSegment rather than silently discarded.
 */
object VoicePipeline {

    private const val TAG = "VoicePipeline"
    private const val LISTEN_TIMEOUT_MS = 8000L
    private const val SILENCE_END_MS = 1500L
    private val NON_SPEECH_MARKER = Regex("^[\\[(<][^\\[\\]()<>]*[\\])>][.!?]?$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Launch a command session coroutine.
     * [onComplete] is always called, even on timeout or exception, so the wake engine can resume.
     */
    fun runCommandSession(context: Context, onComplete: () -> Unit = {}) {
        scope.launch {
            try {
                processSession(context)
            } catch (e: Exception) {
                Log.e(TAG, "Session failed: ${e.message}", e)
            } finally {
                // Guaranteed: wake engine will always get to restart
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

        adapterManager.restorePersistedStack()
        val activeAdapter = adapterManager.currentStackedAdapters().firstOrNull()
        val preferredAdapterId = activeAdapter?.adapterId ?: "torgo_base_adapter_english_v1"
        // Tolerate id drift (adapter renames, a stale build, a bundle installed under a
        // different id) rather than falling back to cloud STT while a usable model is
        // sitting on disk under another name.
        val adapterId = ModelBundleManager.findReadyBundleId(app, preferredAdapterId)
            ?: preferredAdapterId

        // VAD-gated capture with 8s hard timeout — returns to wake listening on silence/timeout
        val pcm = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
            captureWithVad(capture, vad)
        } ?: run {
            Log.w(TAG, "Listen timeout (${LISTEN_TIMEOUT_MS}ms) — no speech detected; returning to wake listening")
            return
        }

        if (pcm.isEmpty()) {
            Log.w(TAG, "Captured 0 samples after VAD trim — nothing to transcribe")
            return
        }
        // A false wake-word trigger still captures a buffer, and trimSilence hands back
        // silence unchanged rather than empty — so without this every false trigger paid
        // for a full NPU encoder+decoder pass and surfaced as "[BLANK_AUDIO]".
        if (!vad.hasSpeech(pcm)) {
            Log.i(TAG, "No speech energy in ${pcm.size} samples — skipping transcription")
            return
        }
        Log.i(TAG, "Captured ${pcm.size} samples (${pcm.size * 1000L / 16000}ms) — transcribing with '$adapterId'")

        val (transcript, avgLogProb, ep) = if (ModelBundleManager.isBundleReady(app, adapterId)) {
            withContext(Dispatchers.Default) {
                val result = engine.transcribe(pcm)
                val seg = result.segments.firstOrNull()
                Triple(result.text, seg?.confidence ?: 0.5f, engine.executionProvider)
            }
        } else {
            Log.w(
                TAG,
                "ONNX bundle missing for '$adapterId' — Google STT fallback. " +
                    "Run calibration, or build the base bundle via ml/export_whisper_mobile.py.",
            )
            // SpeechRecognizer is main-thread-only. Creating it on a Looper-less dispatcher
            // throws inside createExternalRecognizer, which catches it and reports "no
            // recognizer available" — so the whole session died here with no usable reason.
            val text = withContext(Dispatchers.Main) { AndroidSpeechRecognizerFallback.recognize(app) }
            Triple(text, 0.7f, "GoogleSTT")
        }

        Log.i(TAG, "Transcript ($ep): $transcript")
        actOnTranscript(app, transcript, avgLogProb, pcm.size * 1000L / 16000)
    }

    /**
     * Routes a transcript through phrasebook/NLU and, for anything but plain dictation,
     * actually runs it via [ActionExecutor][com.vaanimitra.actions.ActionExecutor] — the
     * step a transcript alone does nothing without. Shared by the wake-word capture path
     * above and any other capture path (e.g. a manual "tap mic" bridge call) that already
     * has a transcript and wants the same real command-execution behavior, not a second,
     * divergent implementation of it.
     */
    suspend fun actOnTranscript(
        context: Context,
        transcript: String,
        avgLogProb: Float,
        durationMs: Long,
    ): TranscriptOutcome {
        val app = context.applicationContext
        val scorer = ConfidenceScorer()
        val phrasebook = VaaniMitraComponents.phrasebookMatcher()
        val parser = VaaniMitraComponents.intentParser()
        val executor = VaaniMitraComponents.actionExecutor(app)

        if (transcript.isBlank()) {
            Log.w(TAG, "Transcript is blank — nothing to act on")
            return TranscriptOutcome.Ignored("empty transcript")
        }
        if (isNonSpeechMarker(transcript)) {
            // Whisper labels silence/noise rather than returning nothing, and those
            // labels were being emitted downstream as if the user had dictated them.
            Log.i(TAG, "Non-speech audio ('$transcript') — ignoring")
            return TranscriptOutcome.Ignored("non-speech marker: $transcript")
        }

        val segment = TranscriptSegment(transcript, 0, durationMs, avgLogProb)
        val scored = scorer.score(listOf(segment), if (avgLogProb > 0) listOf(avgLogProb) else null)
        val finalSeg = scored.first()

        var finalText = transcript
        if (scorer.isLowConfidence(finalSeg)) {
            Log.i(TAG, "Low confidence (${finalSeg.confidence}) — asking user to confirm")
            finalText = ClarificationUi.requestChoice(
                app,
                "Did you mean?",
                listOf(transcript, "(cancel)"),
            ) ?: run {
                Log.i(TAG, "Clarification dismissed — ending session")
                return TranscriptOutcome.Ignored("clarification dismissed")
            }
            if (finalText.contains("cancel")) {
                Log.i(TAG, "User cancelled at clarification")
                return TranscriptOutcome.Cancelled("clarification: user chose cancel")
            }
        }

        val phraseMatch = phrasebook.match(finalText)
        val intent = (if (phraseMatch.matched) phraseMatch.intent else null) ?: parser.parse(finalText)

        // DICTATE_TEXT: no action keyword — emit transcript to RN so UI and dictation consumers
        // receive the text rather than dropping it silently (v1: actions-only but RN still sees it)
        if (intent.action == ActionType.DICTATE_TEXT) {
            Log.d(TAG, "DICTATE_TEXT — emitting transcript to React Native: $finalText")
            RecognitionEventEmitter.instance?.emitTranscriptSegment(
                TranscriptSegment(finalText, 0, durationMs, avgLogProb)
            )
            return TranscriptOutcome.Dictated(finalText)
        }

        val confirmed = ConfirmationGate.confirmIfNeeded(app, intent)
        if (!confirmed) {
            Log.i(TAG, "Action cancelled by user")
            return TranscriptOutcome.Cancelled("confirmation declined or timed out")
        }

        Log.i(TAG, "Executing intent: action=${intent.action} entities=${intent.entities} from '$finalText'")
        val result = withContext(Dispatchers.IO) {
            executor.execute(intent)
        }
        // Discarding this made a failed action indistinguishable from no action at all.
        if (result.success) {
            Log.i(TAG, "Action succeeded: ${result.message}")
        } else {
            Log.w(TAG, "Action FAILED: ${result.message} (accessibilityFallback=${result.requiresAccessibilityFallback})")
        }
        return TranscriptOutcome.ActionExecuted(intent, result)
    }

    /**
     * Whisper annotates non-speech audio instead of returning an empty string —
     * "[BLANK_AUDIO]", "(upbeat music)", "[ Silence ]". Any transcript that is entirely
     * one bracketed token is an annotation, not something the user said.
     */
    private fun isNonSpeechMarker(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || NON_SPEECH_MARKER.matches(t)
    }

    /** Stream until 1.5s silence or 8s hard max. Exits naturally when VAD silence threshold met. */
    private suspend fun captureWithVad(
        capture: AudioCaptureManager,
        vad: VoiceActivityDetector,
    ): ShortArray = withContext(Dispatchers.IO) {
        val maxSamples = AudioCaptureManager.SAMPLE_RATE * 8
        val buffer = mutableListOf<Short>()
        var lastSpeech = System.currentTimeMillis()
        val start = System.currentTimeMillis()

        try {
            capture.streamPcm { chunk ->
                buffer.addAll(chunk.toList())
                val frame = if (chunk.size >= 480) chunk.copyOfRange(0, 480) else chunk
                if (vad.isSpeech(frame)) lastSpeech = System.currentTimeMillis()
                val silenced = System.currentTimeMillis() - lastSpeech > SILENCE_END_MS && buffer.size > 1600
                val maxed = buffer.size >= maxSamples
                val timedOut = System.currentTimeMillis() - start > LISTEN_TIMEOUT_MS
                if (silenced || maxed || timedOut) {
                    val cause = when { silenced -> "silence"; maxed -> "max-samples"; else -> "timeout" }
                    Log.d(TAG, "captureWithVad stopping — reason=$cause samples=${buffer.size}")
                    capture.stopStreaming()
                }
            }
        } finally {
            capture.stopStreaming()
        }

        val arr = buffer.toShortArray()
        vad.trimSilence(arr)
    }
}
