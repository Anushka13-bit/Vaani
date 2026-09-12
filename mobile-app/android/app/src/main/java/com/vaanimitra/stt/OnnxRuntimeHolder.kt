package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime sessions with NNAPI (NPU/GPU) first, CPU fallback.
 * Logs the active execution provider at init time.
 */
class OnnxRuntimeHolder private constructor(
    val encoderSession: OrtSession,
    val decoderSession: OrtSession,
    val executionProvider: String,
    val modelId: String,
    val manifest: ModelBundleManager.MobileManifest,
) {
    private val encoderInputName = manifest.encoderInputName
    private val decoderInputIdsName = manifest.decoderInputIdsName
    private val decoderEncoderHiddenName = manifest.decoderEncoderHiddenName

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    companion object {
        private const val TAG = "OnnxRuntimeHolder"

        @Volatile
        private var instance: OnnxRuntimeHolder? = null

        fun getOrCreate(context: Context, bundleDir: File, modelId: String): OnnxRuntimeHolder? {
            instance?.let { if (it.modelId == modelId) return it }

            val manifest = ModelBundleManager.readManifest(bundleDir) ?: return null
            manifest.incompatibilityReason()?.let { reason ->
                // Refuse rather than run: a mismatched model still produces output,
                // it is just wrong, and that is far harder to diagnose than a refusal.
                Log.e(TAG, "Bundle '$modelId' cannot be run by this build — $reason")
                return null
            }
            val enc = bundleDir.resolve(manifest.encoderFile)
            val dec = bundleDir.resolve(manifest.decoderFile)
            if (!enc.isFile || !dec.isFile) {
                Log.e(TAG, "ONNX files missing in $bundleDir")
                return null
            }

            return try {
                val (encSession, ep) = createSession(context, enc)
                val (decSession, _) = createSession(context, dec)
                Log.i(TAG, "Whisper ONNX loaded model=$modelId EP=$ep")
                OnnxRuntimeHolder(encSession, decSession, ep, modelId, manifest).also { instance = it }
            } catch (e: Exception) {
                Log.e(TAG, "ONNX init failed, trying CPU-only: ${e.message}")
                try {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions()
                    val encSession = env.createSession(enc.absolutePath, opts)
                    val decSession = env.createSession(dec.absolutePath, opts)
                    OnnxRuntimeHolder(encSession, decSession, "CPU", modelId, manifest).also { instance = it }
                } catch (e2: Exception) {
                    Log.e(TAG, "CPU fallback also failed: ${e2.message}")
                    // Failing on both NNAPI and CPU points at the file, not the provider.
                    if (e2.message?.contains("PROTOBUF", ignoreCase = true) == true) {
                        Log.e(
                            TAG,
                            "Model file is not valid ONNX — the bundle is corrupt or was " +
                                "partially downloaded. Re-install it (encoder=${enc.length()}B " +
                                "decoder=${dec.length()}B at $bundleDir).",
                        )
                    }
                    null
                }
            }
        }

        fun release() {
            instance?.encoderSession?.close()
            instance?.decoderSession?.close()
            instance = null
        }

        /**
         * Sentinel file that turns on ORT profiling for the next session:
         *   adb shell run-as com.vaanimitra touch files/ort_profile.on
         * Off by default and free when absent, so this costs nothing in normal use.
         */
        private const val PROFILE_SENTINEL = "ort_profile.on"

        fun profilingEnabled(context: Context): Boolean =
            File(context.filesDir, PROFILE_SENTINEL).exists()

        private fun createSession(
            context: Context,
            modelFile: File,
        ): Pair<OrtSession, String> {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            // "requested" — registering the provider is NOT proof any node runs on it.
            // ORT partitions per node at load time and silently drops unsupported ops
            // back to CPU, so the only honest label here is what we asked for.
            var ep = "CPU"
            try {
                opts.addNnapi()
                ep = "NNAPI-requested"
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI unavailable: ${e.message}")
            }
            if (profilingEnabled(context)) {
                val prefix = File(context.filesDir, "ort_profile_${modelFile.nameWithoutExtension}")
                opts.enableProfiling(prefix.absolutePath)
                Log.i(TAG, "ORT profiling enabled -> $prefix*.json")
            }
            val session = env.createSession(modelFile.absolutePath, opts)
            return Pair(session, ep)
        }
    }

    /**
     * Ends profiling and logs how many executed nodes each provider actually got.
     * This is the only way to tell NPU/NNAPI execution from a silent CPU fallback —
     * the EP string alone reflects what was requested, not what ran.
     */
    fun reportEpPlacement(context: Context) {
        if (!profilingEnabled(context)) return
        try {
            for ((label, session) in listOf("encoder" to encoderSession, "decoder" to decoderSession)) {
                val path = session.endProfiling()
                val json = File(path).takeIf { it.isFile }?.readText() ?: continue
                val tally = Regex("\"provider\"\\s*:\\s*\"([A-Za-z]+)\"")
                    .findAll(json)
                    .map { it.groupValues[1] }
                    .groupingBy { it }
                    .eachCount()
                Log.i(TAG, "EP placement [$label]: ${tally.ifEmpty { mapOf("unknown" to 0) }}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "EP placement report failed: ${e.message}")
        }
    }

    fun runEncoder(mel: FloatArray, nFrames: Int): OnnxTensor {
        // Shape [1, n_mels, n_frames]
        val nMels = MelSpectrogram.N_MELS
        val input = Array(1) { Array(nMels) { FloatArray(nFrames) } }
        for (m in 0 until nMels) {
            for (f in 0 until nFrames) {
                input[0][m][f] = mel[m * nFrames + f]
            }
        }
        val flat = FloatArray(nMels * nFrames)
        var idx = 0
        for (m in 0 until nMels) {
            for (f in 0 until nFrames) {
                flat[idx++] = input[0][m][f]
            }
        }
        val shape = longArrayOf(1, nMels.toLong(), nFrames.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape)
        val result = encoderSession.run(mapOf("input_features" to tensor))
        tensor.close()
        @Suppress("UNCHECKED_CAST")
        return result[0] as OnnxTensor
    }

    fun runDecoderStep(
        inputIds: LongArray,
        encoderHidden: OnnxTensor,
    ): FloatArray {
        val idsShape = longArrayOf(1, inputIds.size.toLong())
        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), idsShape)
        val inputs = mutableMapOf<String, OnnxTensor>(
            decoderInputIdsName to idsTensor,
            decoderEncoderHiddenName to encoderHidden,
        )
        val result = decoderSession.run(inputs)
        idsTensor.close()
        val logitsTensor = result[0] as OnnxTensor
        val shape = logitsTensor.info.shape
        val vocab = shape[shape.size - 1].toInt()
        val logits = FloatArray(vocab)
        val buffer = logitsTensor.floatBuffer
        buffer.position(buffer.capacity() - vocab)
        buffer.get(logits)
        logitsTensor.close()
        result.close()
        return logits
    }

}
