package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import com.vaanimitra.audio.AudioCaptureManager
import java.io.File
import java.security.MessageDigest

/**
 * AdapterManager — tracks active ONNX mobile bundles on device.
 */

enum class AdapterType { USER, LANGUAGE, CLUSTER }

data class AdapterHandle(
    val adapterId: String,
    val version: Int,
    val type: AdapterType,
    val filePath: String,
    val checksum: String,
)

/**
 * Result of transcribing a fixed reference clip before and after an adapter swap.
 * A no-op swap (identical transcript, same adapter weights effectively active) or a
 * silent CPU fallback (executionProvider == "CPU" when NPU/NNAPI was expected) would
 * both pass with no thrown error otherwise — this is what catches that.
 */
data class AdapterVerificationResult(
    val previousAdapterId: String,
    val newAdapterId: String,
    val previousText: String,
    val newText: String,
    val transcriptChanged: Boolean,
    val previousExecutionProvider: String,
    val newExecutionProvider: String,
    val usedNpuAfterSwap: Boolean,
)

interface AdapterManagerInterface {
    fun loadUserAdapter(userId: String): AdapterHandle
    fun loadLanguageAdapter(languageCode: String, serverAdapterId: String? = null): AdapterHandle
    fun currentStackedAdapters(): List<AdapterHandle>
}

class AdapterManager(private val context: Context) : AdapterManagerInterface {

    companion object {
        private const val TAG = "AdapterManager"
        private const val PREFS_NAME = "vaani_adapters"
        private const val KEY_LANGUAGE = "active_language"
        private const val KEY_USER_ID = "active_user_id"
        private const val KEY_CLUSTER_ID = "active_cluster_id"
        private const val KEY_CLUSTER_VERSION = "active_cluster_version"
        private const val KEY_ONNX_ADAPTER_ID = "active_onnx_adapter_id"
        private const val KEY_ONNX_VERSION = "active_onnx_version"
        private const val KEY_ONNX_TYPE = "active_onnx_type"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val stackedAdapters = mutableListOf<AdapterHandle>()

    fun getActiveOnnxAdapterId(): String? = prefs.getString(KEY_ONNX_ADAPTER_ID, null)

    fun persistOnnxAdapter(adapterId: String, version: Int, type: AdapterType) {
        prefs.edit()
            .putString(KEY_ONNX_ADAPTER_ID, adapterId)
            .putInt(KEY_ONNX_VERSION, version)
            .putString(KEY_ONNX_TYPE, type.name)
            .apply()
    }

    fun loadOnnxAdapter(adapterId: String, type: AdapterType, version: Int): AdapterHandle {
        val bundleDir = ModelBundleManager.bundleDir(context, adapterId)
        if (!ModelBundleManager.isBundleReady(context, adapterId)) {
            throw IllegalStateException("ONNX bundle not ready for $adapterId")
        }
        val manifest = ModelBundleManager.readManifest(bundleDir)
        val handle = AdapterHandle(
            adapterId = adapterId,
            version = version,
            type = type,
            filePath = bundleDir.absolutePath,
            checksum = manifest?.let { sha256Dir(bundleDir) } ?: "",
        )
        stackedAdapters.removeAll { it.adapterId == adapterId }
        stackedAdapters.add(handle)
        Log.i(TAG, "Loaded ONNX ${type.name} adapter: $adapterId")

        // Actively bind new adapter to WhisperInferenceEngine and release old ONNX sessions
        try {
            val engine = com.vaanimitra.VaaniMitraComponents.whisperEngine(context)
            engine.activeAdapterId = adapterId
            engine.activeAdapterPath = bundleDir.absolutePath
            OnnxRuntimeHolder.release()
            Log.i(TAG, "Activated ONNX adapter $adapterId in WhisperInferenceEngine")
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify WhisperInferenceEngine of adapter switch: ${e.message}")
        }

        return handle
    }

    /**
     * Loads [adapterId] like [loadOnnxAdapter], but wraps the swap with a real before/after
     * transcription test on [referenceAudioPath] (16kHz mono 16-bit PCM WAV) so a silently
     * no-op adapter load or an unexpected CPU fallback shows up even though no exception
     * would otherwise be thrown.
     */
    suspend fun loadOnnxAdapterVerified(
        adapterId: String,
        type: AdapterType,
        version: Int,
        referenceAudioPath: String,
    ): AdapterVerificationResult {
        val engine = com.vaanimitra.VaaniMitraComponents.whisperEngine(context)
        val previousAdapterId = engine.activeAdapterId
        val referencePcm = readWavPcm(File(referenceAudioPath))

        val previousResult = if (referencePcm.isNotEmpty()) {
            runCatching { engine.transcribe(referencePcm, AudioCaptureManager.SAMPLE_RATE) }
                .onFailure { Log.w(TAG, "Pre-swap reference transcription failed: ${it.message}") }
                .getOrNull()
        } else {
            Log.w(TAG, "Reference audio empty/unreadable at $referenceAudioPath — skipping pre-swap transcription")
            null
        }

        // Perform the actual swap (sets activeAdapterId, releases ONNX sessions).
        loadOnnxAdapter(adapterId, type, version)

        val newResult = if (referencePcm.isNotEmpty()) {
            runCatching { engine.transcribe(referencePcm, AudioCaptureManager.SAMPLE_RATE) }
                .onFailure { Log.e(TAG, "Post-swap reference transcription failed: ${it.message}") }
                .getOrNull()
        } else null

        val previousText = previousResult?.text?.trim() ?: ""
        val newText = newResult?.text?.trim() ?: ""
        val changed = !previousText.equals(newText, ignoreCase = true)
        val prevEp = previousResult?.executionProvider ?: "unknown"
        val newEp = newResult?.executionProvider ?: "unknown"
        val usedNpu = newEp.contains("NNAPI", ignoreCase = true) || newEp.contains("QNN", ignoreCase = true)

        if (!changed) {
            Log.w(
                TAG,
                "Adapter verification: transcript UNCHANGED after swapping $previousAdapterId -> $adapterId " +
                    "(text='$newText'). Personalization may not actually be applied.",
            )
        }
        if (newResult != null && !usedNpu) {
            Log.w(TAG, "Adapter verification: post-swap inference ran on '$newEp', not NPU/NNAPI as expected.")
        }
        Log.i(
            TAG,
            "Adapter verification $previousAdapterId->$adapterId: changed=$changed " +
                "prevEP=$prevEp newEP=$newEp prevText='$previousText' newText='$newText'",
        )

        return AdapterVerificationResult(
            previousAdapterId = previousAdapterId,
            newAdapterId = adapterId,
            previousText = previousText,
            newText = newText,
            transcriptChanged = changed,
            previousExecutionProvider = prevEp,
            newExecutionProvider = newEp,
            usedNpuAfterSwap = usedNpu,
        )
    }

    /** Reads a 16-bit PCM WAV file's audio samples, skipping past its header (found via the "data" chunk). */
    private fun readWavPcm(file: File): ShortArray {
        if (!file.isFile) return ShortArray(0)
        val bytes = file.readBytes()
        if (bytes.size < 44) return ShortArray(0)

        // Locate the "data" sub-chunk instead of assuming a fixed 44-byte header, so files
        // with extra RIFF chunks (e.g. from third-party recorder libraries) still parse correctly.
        var offset = 12 // past "RIFF"+size+"WAVE"
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                dataOffset = offset + 8
                dataSize = chunkSize
                break
            }
            offset += 8 + chunkSize + (chunkSize and 1) // word-aligned
        }
        if (dataOffset < 0) return ShortArray(0)

        val end = minOf(dataOffset + dataSize, bytes.size)
        val sampleCount = (end - dataOffset) / 2
        val samples = ShortArray(sampleCount)
        java.nio.ByteBuffer.wrap(bytes, dataOffset, sampleCount * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(samples)
        return samples
    }

    override fun loadUserAdapter(userId: String): AdapterHandle {
        val adapterId = getActiveOnnxAdapterId() ?: "user_$userId"
        return loadOnnxAdapter(adapterId, AdapterType.USER, prefs.getInt(KEY_ONNX_VERSION, 1))
    }

    override fun loadLanguageAdapter(
        languageCode: String,
        serverAdapterId: String?,
    ): AdapterHandle {
        val adapterId = serverAdapterId ?: getActiveOnnxAdapterId()
            ?: throw IllegalStateException("No ONNX adapter for language $languageCode")
        val type = if (adapterId.startsWith("user_")) AdapterType.USER else AdapterType.CLUSTER
        return loadOnnxAdapter(adapterId, type, prefs.getInt(KEY_ONNX_VERSION, 1))
    }

    fun loadLanguageAdapter(languageCode: String): AdapterHandle =
        loadLanguageAdapter(languageCode, null)

    fun persistActiveConfig(
        languageCode: String? = null,
        userId: String? = null,
        clusterAdapterId: String? = null,
        clusterVersion: Int? = null,
        onnxAdapterId: String? = null,
    ) {
        prefs.edit().apply {
            if (languageCode != null) putString(KEY_LANGUAGE, languageCode)
            if (userId != null) putString(KEY_USER_ID, userId)
            if (clusterAdapterId != null) putString(KEY_CLUSTER_ID, clusterAdapterId)
            if (clusterVersion != null) putInt(KEY_CLUSTER_VERSION, clusterVersion)
            if (onnxAdapterId != null) putString(KEY_ONNX_ADAPTER_ID, onnxAdapterId)
        }.apply()
    }

    fun restorePersistedStack(): List<AdapterHandle> {
        stackedAdapters.clear()
        val onnxId = getActiveOnnxAdapterId()
        if (onnxId == null) return emptyList()

        val typeName = prefs.getString(KEY_ONNX_TYPE, AdapterType.CLUSTER.name) ?: AdapterType.CLUSTER.name
        val type = try {
            AdapterType.valueOf(typeName)
        } catch (_: Exception) {
            AdapterType.CLUSTER
        }
        val version = prefs.getInt(KEY_ONNX_VERSION, 1)

        return try {
            if (ModelBundleManager.isBundleReady(context, onnxId)) {
                listOf(loadOnnxAdapter(onnxId, type, version))
            } else {
                Log.w(TAG, "Persisted ONNX bundle missing on disk: $onnxId")
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore ONNX adapter: ${e.message}")
            emptyList()
        }
    }

    override fun currentStackedAdapters(): List<AdapterHandle> = stackedAdapters.toList()

    private fun sha256Dir(dir: File): String {
        val manifest = File(dir, "mobile_manifest.json")
        return if (manifest.isFile) sha256(manifest) else ""
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
