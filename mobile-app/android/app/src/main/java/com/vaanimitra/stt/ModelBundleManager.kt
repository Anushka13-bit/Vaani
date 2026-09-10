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
    )

    fun bundleDir(context: Context, adapterId: String): File =
        File(context.filesDir, "$MODELS_DIR/$adapterId").also { it.mkdirs() }

    fun isBundleReady(context: Context, adapterId: String): Boolean {
        val dir = bundleDir(context, adapterId)
        val manifest = readManifest(dir) ?: return false
        return dir.resolve(manifest.encoderFile).isFile &&
            dir.resolve(manifest.decoderFile).isFile
    }

    fun readManifest(dir: File): MobileManifest? {
        val file = File(dir, "mobile_manifest.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            MobileManifest(
                adapterId = json.getString("adapter_id"),
                encoderFile = json.getString("encoder_file"),
                decoderFile = json.getString("decoder_file"),
                mergedLora = json.optBoolean("merged_lora", true),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bad manifest: ${e.message}")
            null
        }
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
        return readManifest(destDir)
            ?: throw IllegalStateException("mobile_manifest.json missing from bundle")
    }
}
