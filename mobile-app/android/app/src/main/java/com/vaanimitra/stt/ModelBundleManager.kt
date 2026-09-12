package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Manages sherpa-onnx Whisper mobile bundles (encoder/decoder/tokens + manifest) on
 * device storage.
 */
object ModelBundleManager {

    private const val TAG = "ModelBundleManager"
    private const val MODELS_DIR = "whisper_models"

    // The only manifest format this runtime knows how to execute. See
    // docs/SHERPA_ONNX_CONTRACT.md — the backend export pipeline and this runtime are
    // built by separate agents against that shared contract, so this string is a fixed
    // point between them, not a local convention.
    const val SUPPORTED_FORMAT = "sherpa-onnx-whisper-kv-cache"

    // Pinned sherpa-onnx release the bundled AAR was built from (see build.gradle and
    // docs/SHERPA_ONNX_CONTRACT.md). Bundles built against a different sherpa-onnx
    // version are not rejected outright — the KV-cache decoder ABI has been stable
    // across recent 1.13.x releases — but a mismatch is logged so a real
    // incompatibility (a future breaking export change) doesn't look like silent
    // success.
    private const val PINNED_SHERPA_ONNX_VERSION = "1.13.8"

    private const val DEFAULT_SAMPLE_RATE = 16_000
    private const val DEFAULT_N_MELS = 80
    private const val DEFAULT_LANGUAGE = "en"
    private const val DEFAULT_TASK = "transcribe"

    /**
     * Runtime contract for a sherpa-onnx Whisper model bundle.
     *
     * Every field here is read straight from `mobile_manifest.json`, never assumed —
     * that is what lets a different Whisper variant (different language, task, or
     * quantization) be dropped in without touching this code.
     */
    data class MobileManifest(
        val adapterId: String,
        val format: String,
        val encoderFile: String,
        val decoderFile: String,
        val tokensFile: String,
        val mergedLora: Boolean,
        val encoderSha: String? = null,
        val decoderSha: String? = null,
        val tokensSha: String? = null,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val nMels: Int = DEFAULT_N_MELS,
        val language: String = DEFAULT_LANGUAGE,
        val task: String = DEFAULT_TASK,
        val sherpaOnnxVersion: String? = null,
    ) {
        /**
         * Why the runtime cannot execute this bundle, or null when it can.
         *
         * A bundle built for the old cacheless ONNX runtime (`"format": "onnx"`, or no
         * `format` field at all — every bundle exported before this migration) must be
         * rejected here, not silently fed to sherpa-onnx: the old bundle's encoder/decoder
         * files are a completely different graph shape (no KV cache, different I/O names)
         * and sherpa-onnx would either fail to load them or, worse, load them and produce
         * confident nonsense.
         */
        fun incompatibilityReason(): String? = when {
            format != SUPPORTED_FORMAT ->
                "manifest format '$format' is not supported by this runtime (only " +
                    "'$SUPPORTED_FORMAT' is) — this looks like a bundle built for the " +
                    "old cacheless ONNX runtime and must be re-exported"
            encoderFile.isBlank() -> "manifest carries no encoder_file"
            decoderFile.isBlank() -> "manifest carries no decoder_file"
            tokensFile.isBlank() -> "manifest carries no tokens_file"
            language.isBlank() -> "manifest carries no language"
            task.isBlank() -> "manifest carries no task"
            else -> null
        }

        /** Non-fatal: logs when the bundle was exported against a different sherpa-onnx
         * release than the one this build's AAR is pinned to, in case that ever matters
         * for a future KV-cache ABI change. */
        fun logVersionMismatchIfAny() {
            if (sherpaOnnxVersion != null && sherpaOnnxVersion != PINNED_SHERPA_ONNX_VERSION) {
                Log.w(
                    TAG,
                    "Bundle '$adapterId' was exported with sherpa-onnx $sherpaOnnxVersion, " +
                        "this build is pinned to $PINNED_SHERPA_ONNX_VERSION — proceeding, " +
                        "but a KV-cache ABI change between those versions would surface as " +
                        "a load or decode failure, not a silent one.",
                )
            }
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
            dir.resolve(manifest.decoderFile).isNonEmptyFile() &&
            dir.resolve(manifest.tokensFile).isNonEmptyFile()
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
            val manifest = MobileManifest(
                adapterId = json.getString("adapter_id"),
                // Old bundles never had a "format" field at all — default to "" (not
                // e.g. "onnx") so a missing field always fails the compatibility check
                // rather than accidentally matching some future default.
                format = json.optString("format", ""),
                encoderFile = json.optString("encoder_file", ""),
                decoderFile = json.optString("decoder_file", ""),
                tokensFile = json.optString("tokens_file", ""),
                mergedLora = json.optBoolean("merged_lora", true),
                encoderSha = checksums?.optString("encoder")?.takeIf { it.isNotBlank() },
                decoderSha = checksums?.optString("decoder")?.takeIf { it.isNotBlank() },
                tokensSha = checksums?.optString("tokens")?.takeIf { it.isNotBlank() },
                sampleRate = json.optInt("sample_rate", DEFAULT_SAMPLE_RATE),
                nMels = json.optInt("n_mels", DEFAULT_N_MELS),
                language = json.optString("language", DEFAULT_LANGUAGE),
                task = json.optString("task", DEFAULT_TASK),
                sherpaOnnxVersion = json.optString("sherpa_onnx_version", "").takeIf { it.isNotBlank() },
            )
            manifest.logVersionMismatchIfAny()
            manifest
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
     * as a native sherpa-onnx load failure deep inside the JNI layer.
     */
    fun verifyChecksums(dir: File, manifest: MobileManifest) {
        val targets = listOfNotNull(
            manifest.encoderSha?.let { manifest.encoderFile to it },
            manifest.decoderSha?.let { manifest.decoderFile to it },
            manifest.tokensSha?.let { manifest.tokensFile to it },
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
        manifest.incompatibilityReason()?.let { reason ->
            throw IllegalStateException("Bundle '${manifest.adapterId}' is incompatible: $reason")
        }
        verifyChecksums(destDir, manifest)
        return manifest
    }
}
