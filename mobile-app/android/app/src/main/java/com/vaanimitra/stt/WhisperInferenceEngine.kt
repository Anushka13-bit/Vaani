package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WhisperInferenceEngine — wraps ONNX Runtime Mobile / whisper.cpp for on-device STT.
 *
 * STUB implementation: returns a deterministic mock result so the RN bridge and
 * PersonalizedRecognitionService can be tested end-to-end before the ONNX model
 * is integrated.
 *
 * TODO: Replace stub body with real ONNX Runtime inference:
 *   1. Add onnxruntime-android dependency to build.gradle
 *   2. Copy whisper-small.onnx to assets/
 *   3. Call OrtEnvironment.getEnvironment().createSession(...)
 *   4. Pre-process pcmAudio → log-mel spectrogram
 *   5. Run encoder → decoder with greedy/beam decode
 *   6. Apply LoRA adapter weights via AdapterManager before inference
 *
 * See: https://github.com/microsoft/onnxruntime  (mobile runtime)
 *      https://github.com/ggerganov/whisper.cpp   (alternative C++ path via JNI)
 */
class WhisperInferenceEngine(private val context: Context) : SttEngine {

    companion object {
        private const val TAG = "WhisperInferenceEngine"
        // Model asset path. Copy whisper-small.onnx here before enabling real inference.
        private const val MODEL_ASSET = "whisper-small.onnx"
    }

    // Reference to the currently loaded LoRA adapter (set by AdapterManager)
    @Volatile
    var activeAdapterPath: String? = null

    fun isOnnxModelAvailable(): Boolean {
        return try {
            context.assets.open(MODEL_ASSET).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * STUB — returns a mock TranscriptionResult.
     * Replace this implementation with real ONNX Runtime inference (see TODO above).
     */
    override suspend fun transcribe(
        pcmAudio: ShortArray,
        sampleRate: Int,
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        Log.d(TAG, "transcribe() called — STUB. samples=${pcmAudio.size}, adapter=$activeAdapterPath")

        // Simulate inference delay
        kotlinx.coroutines.delay(300)

        // STUB result — replace with real decoder output
        val mockText = "[STUB] Hello, this is a mock transcription result."
        TranscriptionResult(
            text = mockText,
            languageDetected = "en",
            segments = listOf(
                TranscriptSegment(
                    text = mockText,
                    startMs = 0L,
                    endMs = (pcmAudio.size.toLong() * 1000L / sampleRate),
                    confidence = 0.85f,
                )
            ),
        )
    }

    // ── TODO: Real implementation outline ────────────────────────────────────
    //
    // private lateinit var ortSession: OrtSession
    //
    // fun loadModel() {
    //     val env = OrtEnvironment.getEnvironment()
    //     val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
    //     ortSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    //     Log.i(TAG, "Whisper model loaded from assets/$MODEL_ASSET")
    // }
    //
    // private fun pcmToLogMel(pcm: ShortArray): FloatArray { /* mel spectrogram */ }
    // private fun runEncoder(mel: FloatArray): OnnxTensor { /* ... */ }
    // private fun runDecoder(encoderOut: OnnxTensor): List<Int> { /* greedy decode */ }
    // private fun decodeTokens(tokens: List<Int>): String { /* BPE decode */ }
    // ─────────────────────────────────────────────────────────────────────────
}
