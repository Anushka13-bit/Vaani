package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Manages mobile ONNX bundles (encoder/decoder + manifest) on device storage.
 */
object ModelBundleManager {

    private const val TAG = "ModelBundleManager"
    private const val MODELS_DIR = "whisper_models"

    // Describe the Whisper-small cacheless export this app shipped with. Bundles
    // exported before the manifest carried a runtime contract fall back to these,
    // so upgrading the exporter does not strand a model already on a device.
    private const val DEFAULT_ARCHITECTURE = "whisper-encoder-decoder"
    private const val DEFAULT_SAMPLE_RATE = 16_000
    private const val DEFAULT_N_MELS = 80
    private const val DEFAULT_N_FFT = 400
    private const val DEFAULT_HOP = 160
    private const val DEFAULT_N_FRAMES = 3000
    private const val DEFAULT_EOT = 50257L
    private const val DEFAULT_MAX_NEW_TOKENS = 128
    private const val DEFAULT_ENCODER_INPUT = "input_features"
    private const val DEFAULT_DECODER_INPUT_IDS = "input_ids"
    private const val DEFAULT_DECODER_ENCODER_HIDDEN = "encoder_hidden_states"
    private val DEFAULT_PROMPT_TOKENS = listOf(50258L, 50259L, 50359L, 50363L)

    /**
     * Runtime contract for a model bundle.
     *
     * Defaults describe the Whisper-small cacheless export this app shipped with, so
     * bundles produced before the exporter emitted these fields keep working. Anything
     * the decode path used to assume is carried here instead, which is what lets a
     * different model be dropped in without touching code.
     */
    data class MobileManifest(
        val adapterId: String,
        val encoderFile: String,
        val decoderFile: String,
        val mergedLora: Boolean,
        val encoderSha: String? = null,
        val decoderSha: String? = null,
        val architecture: String = DEFAULT_ARCHITECTURE,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val nMels: Int = DEFAULT_N_MELS,
        val nFft: Int = DEFAULT_N_FFT,
        val hopLength: Int = DEFAULT_HOP,
        val nFrames: Int = DEFAULT_N_FRAMES,
        val decoderKvCache: Boolean = false,
        val promptTokenIds: List<Long> = DEFAULT_PROMPT_TOKENS,
        val eotTokenId: Long = DEFAULT_EOT,
        val maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
        val encoderInputName: String = DEFAULT_ENCODER_INPUT,
        val decoderInputIdsName: String = DEFAULT_DECODER_INPUT_IDS,
        val decoderEncoderHiddenName: String = DEFAULT_DECODER_ENCODER_HIDDEN,
    ) {
        /**
         * Why the runtime cannot execute this bundle, or null when it can.
         *
         * Without this a mismatched model does not fail — it produces confident
         * nonsense: wrong mel bins reshape into the encoder anyway, and wrong special
         * token ids decode to garbage or never emit end-of-text. Refusing with a
         * reason is the only honest option until the corresponding support exists.
         */
        fun incompatibilityReason(): String? = when {
            architecture != "whisper-encoder-decoder" ->
                "architecture '$architecture' is not implemented (only whisper-encoder-decoder)"
            decoderKvCache ->
                "decoder exports a KV cache; this runtime feeds only input_ids + " +
                    "encoder_hidden_states and reads full-sequence logits"
            nMels != MelSpectrogram.N_MELS ->
                "model needs $nMels mel bins, feature extractor produces ${MelSpectrogram.N_MELS}"
            nFft != MelSpectrogram.N_FFT ->
                "model needs n_fft=$nFft, feature extractor uses ${MelSpectrogram.N_FFT}"
            hopLength != MelSpectrogram.HOP ->
                "model needs hop=$hopLength, feature extractor uses ${MelSpectrogram.HOP}"
            nFrames != MelSpectrogram.N_FRAMES ->
                "model needs $nFrames frames, feature extractor produces ${MelSpectrogram.N_FRAMES}"
            sampleRate != MelSpectrogram.SAMPLE_RATE ->
                "model expects ${sampleRate}Hz audio, capture is ${MelSpectrogram.SAMPLE_RATE}Hz"
            promptTokenIds.isEmpty() -> "manifest carries no prompt token ids"
            else -> null
        }
    }

    fun bundleDir(context: Context, adapterId: String): File =
        File(context.filesDir, "$MODELS_DIR/$adapterId").also { it.mkdirs() }

    fun isBundleReady(context: Context, adapterId: String): Boolean {
        val dir = bundleDir(context, adapterId)
        val manifest = readManifest(dir) ?: return false
        // Size check only — hashing ~200MB on every wake word would be far too slow.
        // Content is verified once at extract time by verifyChecksums().
        return dir.resolve(manifest.encoderFile).isNonEmptyFile() &&
            dir.resolve(manifest.decoderFile).isNonEmptyFile()
    }

    private fun File.isNonEmptyFile(): Boolean = isFile && length() > 0

    /**
     * Returns [preferred] when its bundle is usable, otherwise any other bundle present
     * on disk. Adapter ids drift (renames, a stale APK, a bundle installed under a
     * different id), and without this the app reports "no model" while a perfectly good
     * one sits in the next directory.
     */
    fun findReadyBundleId(context: Context, preferred: String): String? {
        if (isBundleReady(context, preferred)) return preferred

        val root = File(context.filesDir, MODELS_DIR)
        val alternative = root.listFiles { f: File -> f.isDirectory }
            ?.map { it.name }
            ?.firstOrNull { it != preferred && isBundleReady(context, it) }

        if (alternative != null) {
            Log.w(TAG, "Bundle '$preferred' not usable — falling back to '$alternative' found on disk")
        }
        return alternative
    }

    fun readManifest(dir: File): MobileManifest? {
        val file = File(dir, "mobile_manifest.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val checksums = json.optJSONObject("checksums")
            val io = json.optJSONObject("io_names")
            val dec = json.optJSONObject("decoding")
            MobileManifest(
                adapterId = json.getString("adapter_id"),
                encoderFile = json.getString("encoder_file"),
                decoderFile = json.getString("decoder_file"),
                mergedLora = json.optBoolean("merged_lora", true),
                encoderSha = checksums?.optString("encoder")?.takeIf { it.isNotBlank() },
                decoderSha = checksums?.optString("decoder")?.takeIf { it.isNotBlank() },
                architecture = json.optString("architecture", DEFAULT_ARCHITECTURE),
                sampleRate = json.optInt("sample_rate", DEFAULT_SAMPLE_RATE),
                nMels = json.optInt("n_mels", DEFAULT_N_MELS),
                nFft = json.optInt("n_fft", DEFAULT_N_FFT),
                hopLength = json.optInt("hop_length", DEFAULT_HOP),
                nFrames = json.optInt("n_frames", DEFAULT_N_FRAMES),
                decoderKvCache = json.optBoolean("decoder_kv_cache", false),
                promptTokenIds = dec?.optJSONArray("prompt_token_ids")?.let { arr ->
                    (0 until arr.length()).map { arr.getLong(it) }
                }?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROMPT_TOKENS,
                eotTokenId = dec?.optLong("eot_token_id", DEFAULT_EOT) ?: DEFAULT_EOT,
                maxNewTokens = dec?.optInt("max_new_tokens", DEFAULT_MAX_NEW_TOKENS) ?: DEFAULT_MAX_NEW_TOKENS,
                encoderInputName = io?.optString("encoder_input", DEFAULT_ENCODER_INPUT)
                    ?: DEFAULT_ENCODER_INPUT,
                decoderInputIdsName = io?.optString("decoder_input_ids", DEFAULT_DECODER_INPUT_IDS)
                    ?: DEFAULT_DECODER_INPUT_IDS,
                decoderEncoderHiddenName = io?.optString("decoder_encoder_hidden", DEFAULT_DECODER_ENCODER_HIDDEN)
                    ?: DEFAULT_DECODER_ENCODER_HIDDEN,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bad manifest: ${e.message}")
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks extracted models against the checksums the exporter recorded. A truncated
     * or partial download otherwise stays undetected until inference, where it surfaces
     * as ORT_INVALID_PROTOBUF from deep inside ONNX Runtime.
     */
    fun verifyChecksums(dir: File, manifest: MobileManifest) {
        val targets = listOfNotNull(
            manifest.encoderSha?.let { manifest.encoderFile to it },
            manifest.decoderSha?.let { manifest.decoderFile to it },
        )
        if (targets.isEmpty()) {
            Log.w(TAG, "Manifest has no checksums — skipping bundle integrity check")
            return
        }
        for ((name, expected) in targets) {
            val file = File(dir, name)
            if (!file.isFile) throw IllegalStateException("Bundle is missing $name")
            val actual = sha256Of(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                file.delete() // don't leave a corrupt model that later loads as garbage
                throw IllegalStateException(
                    "Bundle file $name is corrupt (checksum mismatch) — the download was " +
                        "incomplete. Expected $expected but got $actual.",
                )
            }
        }
        Log.i(TAG, "Bundle integrity verified (${targets.size} file(s))")
    }

    fun extractZip(zipBytes: ByteArray, destDir: File): MobileManifest {
        destDir.mkdirs()
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
        val manifest = readManifest(destDir)
            ?: throw IllegalStateException("mobile_manifest.json missing from bundle")
        verifyChecksums(destDir, manifest)
        return manifest
    }
}
