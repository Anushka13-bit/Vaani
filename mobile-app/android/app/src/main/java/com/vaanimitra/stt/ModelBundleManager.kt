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

    data class MobileManifest(
        val adapterId: String,
        val encoderFile: String,
        val decoderFile: String,
        val mergedLora: Boolean,
        val encoderSha: String? = null,
        val decoderSha: String? = null,
    )

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
            MobileManifest(
                adapterId = json.getString("adapter_id"),
                encoderFile = json.getString("encoder_file"),
                decoderFile = json.getString("decoder_file"),
                mergedLora = json.optBoolean("merged_lora", true),
                encoderSha = checksums?.optString("encoder")?.takeIf { it.isNotBlank() },
                decoderSha = checksums?.optString("decoder")?.takeIf { it.isNotBlank() },
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
