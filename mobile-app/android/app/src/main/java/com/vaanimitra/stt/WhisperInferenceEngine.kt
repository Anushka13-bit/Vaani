package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import com.vaanimitra.audio.AudioCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Whisper STT via sherpa-onnx, running the merged TORGO LoRA KV-cache
 * encoder/decoder (NNAPI-requested, CPU fallback — see SherpaOnnxWhisperRuntime).
 */
class WhisperInferenceEngine(private val context: Context) : SttEngine {

    companion object {
        private const val TAG = "WhisperInferenceEngine"
    }

    @Volatile
    var activeAdapterPath: String? = null

    @Volatile
    var activeAdapterId: String = "torgo_base_adapter_english_v1"

    @Volatile
    var executionProvider: String = "none"

    private val confidenceScorer = ConfidenceScorer()

    fun isModelReady(): Boolean =
        ModelBundleManager.isBundleReady(context, activeAdapterId)

    fun isOnnxModelAvailable(): Boolean = isModelReady()

    override suspend fun transcribe(
        pcmAudio: ShortArray,
        sampleRate: Int,
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        if (sampleRate != AudioCaptureManager.SAMPLE_RATE) {
            Log.w(TAG, "Unexpected sample rate $sampleRate — expected ${AudioCaptureManager.SAMPLE_RATE}")
        }

        if (!isModelReady()) {
            Log.e(TAG, "sherpa-onnx bundle not ready for $activeAdapterId")
            throw IllegalStateException("Mobile sherpa-onnx bundle not downloaded. Complete calibration first.")
        }

        val runtime = SherpaOnnxWhisperRuntime(context, activeAdapterId)
        val decoded = runtime.transcribe(pcmAudio)
            ?: throw IllegalStateException("sherpa-onnx inference failed")

        executionProvider = decoded.executionProvider
        activeAdapterPath = ModelBundleManager.bundleDir(context, activeAdapterId).absolutePath

        Log.i(TAG, "sherpa-onnx transcribe EP=${decoded.executionProvider} adapter=$activeAdapterId " +
            "text='${decoded.text.take(40)}'")

        // sherpa-onnx's greedy-search Whisper decode surfaces no per-utterance log-prob
        // or score (see SherpaOnnxWhisperRuntime.DecodeResult) — unlike the old ONNX
        // runtime's exp(avgLogProb), there is no real confidence signal to compute here.
        // ConfidenceScorer's text-derived heuristic (tokenLogProbs = null) is an honest
        // placeholder, not a rediscovered real confidence.
        val segments = confidenceScorer.score(
            listOf(
                TranscriptSegment(
                    text = decoded.text,
                    startMs = 0,
                    endMs = pcmAudio.size.toLong() * 1000 / sampleRate,
                    confidence = 0f,
                ),
            ),
        )

        TranscriptionResult(
            text = decoded.text,
            languageDetected = "en",
            segments = segments,
            avgLogProb = null,
            executionProvider = decoded.executionProvider,
        )
    }
}
