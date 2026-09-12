package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Whisper STT with merged TORGO LoRA ONNX (NNAPI → CPU fallback).
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

    fun isModelReady(): Boolean =
        ModelBundleManager.isBundleReady(context, activeAdapterId)

    fun isOnnxModelAvailable(): Boolean = isModelReady()

    override suspend fun transcribe(
        pcmAudio: ShortArray,
        sampleRate: Int,
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        if (sampleRate != MelSpectrogram.SAMPLE_RATE) {
            Log.w(TAG, "Unexpected sample rate $sampleRate — expected 16000")
        }

        if (!isModelReady()) {
            Log.e(TAG, "Mobile ONNX bundle not ready for $activeAdapterId")
            throw IllegalStateException("Mobile ONNX bundle not downloaded. Complete calibration first.")
        }

        val runtime = OnnxWhisperRuntime(context, activeAdapterId)
        val decoded = runtime.transcribe(pcmAudio)
            ?: throw IllegalStateException("ONNX inference failed")

        executionProvider = decoded.executionProvider
        activeAdapterPath = ModelBundleManager.bundleDir(context, activeAdapterId).absolutePath

        Log.i(TAG, "ONNX transcribe EP=${decoded.executionProvider} adapter=$activeAdapterId " +
            "logProb=${decoded.avgLogProb} text='${decoded.text.take(40)}'")

        val confidence = expProb(decoded.avgLogProb)
        TranscriptionResult(
            text = decoded.text,
            languageDetected = "en",
            segments = listOf(
                TranscriptSegment(
                    text = decoded.text,
                    startMs = 0,
                    endMs = pcmAudio.size.toLong() * 1000 / sampleRate,
                    confidence = confidence,
                ),
            ),
            avgLogProb = decoded.avgLogProb,
            executionProvider = decoded.executionProvider,
        )
    }

    private fun expProb(logProb: Float): Float {
        if (logProb <= -10f) return 0.1f
        return kotlin.math.exp(logProb.coerceIn(-10f, 0f)).coerceIn(0f, 1f)
    }
}
