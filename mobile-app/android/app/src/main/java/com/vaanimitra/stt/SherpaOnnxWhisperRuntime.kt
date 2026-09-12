package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Whisper STT via sherpa-onnx's [OfflineRecognizer] (k2-fsa/sherpa-onnx, Apache 2.0),
 * running the merged-LoRA KV-cache encoder/decoder produced by the backend export
 * pipeline. Replaces the hand-rolled MelSpectrogram/decode-loop/WhisperTokenizer
 * pipeline that used to sit here — sherpa-onnx does mel extraction, KV-cache greedy
 * decoding, and tokenizer decode internally in C++, so none of that lives in this app
 * anymore. See docs/SHERPA_ONNX_CONTRACT.md for the pinned sherpa-onnx version and the
 * manifest schema this reads.
 */
class SherpaOnnxWhisperRuntime(
    private val context: Context,
    private val adapterId: String,
) {
    companion object {
        private const val TAG = "SherpaOnnxWhisperRuntime"

        /** Releases the cached recognizer, e.g. before swapping to a different adapter. */
        fun release() = RecognizerCache.release()
    }

    /**
     * [avgLogProb] does not exist here on purpose: sherpa-onnx's OfflineRecognizer API
     * (OfflineRecognizerResult: text, tokens, timestamps, lang, emotion, event,
     * durations — see sherpa-onnx/kotlin-api/OfflineRecognizer.kt at the pinned tag)
     * exposes no per-utterance or per-token score for its greedy-search Whisper decode.
     * The only "confidence" field anywhere in sherpa-onnx's C API belongs to offline
     * speaker-diarization clustering, not ASR. The old runtime's confidence came from
     * averaging its own decode-loop log-probs, which sherpa-onnx's internal C++ decode
     * loop never surfaces back to Kotlin — there is nothing honest to put here instead
     * of leaving it out.
     */
    data class DecodeResult(
        val text: String,
        val executionProvider: String,
    )

    fun transcribe(pcm: ShortArray): DecodeResult? {
        val bundleDir = ModelBundleManager.bundleDir(context, adapterId)
        val cached = RecognizerCache.getOrCreate(bundleDir, adapterId) ?: return null

        // sherpa-onnx's OfflineStream wants normalized float samples, same convention
        // as the old MelSpectrogram input (PCM16 / 32768).
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }

        val stream = cached.recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, cached.manifest.sampleRate)
            cached.recognizer.decode(stream)
            val result = cached.recognizer.getResult(stream)
            DecodeResult(result.text.trim(), cached.executionProvider)
        } catch (e: Exception) {
            Log.e(TAG, "sherpa-onnx decode failed for $adapterId: ${e.message}")
            null
        } finally {
            stream.release()
        }
    }

    /**
     * Caches one [OfflineRecognizer] per active adapter, mirroring how the old
     * OnnxRuntimeHolder cached ONNX sessions per adapter — constructing a recognizer
     * loads and initializes the encoder+decoder ONNX graphs, which is far too slow to
     * repeat per utterance.
     */
    private object RecognizerCache {
        private const val TAG = "SherpaOnnxRecognizerCache"

        class Cached(
            val recognizer: OfflineRecognizer,
            val modelId: String,
            val executionProvider: String,
            val manifest: ModelBundleManager.MobileManifest,
        )

        @Volatile
        private var instance: Cached? = null

        fun getOrCreate(bundleDir: File, modelId: String): Cached? {
            instance?.let { if (it.modelId == modelId) return it }

            val manifest = ModelBundleManager.readManifest(bundleDir) ?: return null
            manifest.incompatibilityReason()?.let { reason ->
                // Refuse rather than run: a mismatched bundle still produces output,
                // it is just wrong, and that is far harder to diagnose than a refusal.
                Log.e(TAG, "Bundle '$modelId' cannot be run by this build — $reason")
                return null
            }

            val encoder = bundleDir.resolve(manifest.encoderFile)
            val decoder = bundleDir.resolve(manifest.decoderFile)
            val tokens = bundleDir.resolve(manifest.tokensFile)
            if (!encoder.isFile || !decoder.isFile || !tokens.isFile) {
                Log.e(TAG, "sherpa-onnx bundle files missing in $bundleDir")
                return null
            }

            release()

            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = manifest.language,
                    task = manifest.task,
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
            )
            val featConfig = FeatureConfig(
                sampleRate = manifest.sampleRate,
                featureDim = manifest.nMels,
            )

            // "nnapi" is a real onnxruntime execution provider sherpa-onnx's C++ layer
            // knows how to request (sherpa-onnx/csrc/session.cc) and it fails soft: if
            // NNAPI is unavailable it logs a warning and silently continues on CPU
            // rather than throwing, so trying it first costs nothing on devices where
            // it isn't usable. QNN is NOT attempted here — that needs sherpa-onnx built
            // from source against the Qualcomm SDK, out of scope for this pass and
            // already tracked in docs/QNN_INTEGRATION.md.
            //
            // What this can't do: prove NNAPI actually placed any node on the NPU.
            // sherpa-onnx's Kotlin API has no equivalent of the old OnnxRuntimeHolder's
            // ORT-profiling EP-placement report, so "nnapi-requested" below reflects
            // only what we asked for, exactly as the old runtime's "NNAPI-requested"
            // label did before it had profiling wired up.
            return try {
                val config = OfflineRecognizerConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig.copy(provider = "nnapi"),
                )
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "sherpa-onnx Whisper recognizer loaded model=$modelId provider=nnapi-requested")
                Cached(recognizer, modelId, "nnapi-requested", manifest).also { instance = it }
            } catch (e: Exception) {
                Log.w(TAG, "nnapi init failed, falling back to cpu: ${e.message}")
                try {
                    val config = OfflineRecognizerConfig(
                        featConfig = featConfig,
                        modelConfig = modelConfig.copy(provider = "cpu"),
                    )
                    val recognizer = OfflineRecognizer(assetManager = null, config = config)
                    Log.i(TAG, "sherpa-onnx Whisper recognizer loaded model=$modelId provider=cpu")
                    Cached(recognizer, modelId, "cpu", manifest).also { instance = it }
                } catch (e2: Exception) {
                    Log.e(TAG, "cpu fallback also failed: ${e2.message}")
                    null
                }
            }
        }

        fun release() {
            instance?.recognizer?.release()
            instance = null
        }
    }
}
