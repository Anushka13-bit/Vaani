package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * AdapterManager — loads, stores, and stacks LoRA adapter files (§2.3).
 *
 * Adapter files are downloaded from the backend, saved to encrypted internal storage
 * (KeystoreCrypto handles encryption — see security/KeystoreCrypto.kt), and registered
 * here so WhisperInferenceEngine knows which weights to apply.
 *
 * Supports stacking: a language/cluster adapter + a user adapter applied simultaneously.
 */

// ── Data classes ─────────────────────────────────────────────────────────────

enum class AdapterType { USER, LANGUAGE, CLUSTER }

data class AdapterHandle(
    val adapterId: String,
    val version: Int,
    val type: AdapterType,
    val filePath: String,
    val checksum: String,
)

// ── Interface ─────────────────────────────────────────────────────────────────

interface AdapterManagerInterface {
    suspend fun loadUserAdapter(userId: String): AdapterHandle
    suspend fun loadLanguageAdapter(languageCode: String): AdapterHandle
    fun currentStackedAdapters(): List<AdapterHandle>
}

// ── Implementation ────────────────────────────────────────────────────────────

class AdapterManager(private val context: Context) : AdapterManagerInterface {

    companion object {
        private const val TAG = "AdapterManager"
        private const val ADAPTERS_DIR = "lora_adapters"
    }

    private val adaptersDir: File by lazy {
        File(context.filesDir, ADAPTERS_DIR).also { it.mkdirs() }
    }

    private val stackedAdapters = mutableListOf<AdapterHandle>()

    /**
     * Load the active USER adapter for [userId] from internal storage.
     * The file must have been downloaded and saved previously via [saveAdapterFile].
     */
    override suspend fun loadUserAdapter(userId: String): AdapterHandle {
        val file = File(adaptersDir, "user_${userId}.bin")
        return if (file.exists()) {
            val handle = AdapterHandle(
                adapterId = "user_${userId}",
                version = 1,
                type = AdapterType.USER,
                filePath = file.absolutePath,
                checksum = sha256(file),
            )
            stackedAdapters.removeAll { it.type == AdapterType.USER }
            stackedAdapters.add(handle)
            Log.i(TAG, "Loaded USER adapter: ${handle.adapterId}")
            handle
        } else {
            Log.w(TAG, "USER adapter not found for userId=$userId. Download first.")
            throw IllegalStateException("User adapter not found for userId=$userId. Run calibration first.")
        }
    }

    /**
     * Load a LANGUAGE or CLUSTER adapter by its [languageCode] identifier.
     */
    override suspend fun loadLanguageAdapter(languageCode: String): AdapterHandle {
        val file = File(adaptersDir, "lang_${languageCode}.bin")
        return if (file.exists()) {
            val handle = AdapterHandle(
                adapterId = "lang_${languageCode}",
                version = 1,
                type = AdapterType.LANGUAGE,
                filePath = file.absolutePath,
                checksum = sha256(file),
            )
            stackedAdapters.removeAll { it.type == AdapterType.LANGUAGE || it.type == AdapterType.CLUSTER }
            stackedAdapters.add(handle)
            Log.i(TAG, "Loaded LANGUAGE adapter: ${handle.adapterId}")
            handle
        } else {
            Log.w(TAG, "Language adapter not found for lang=$languageCode")
            throw IllegalStateException("Language adapter not found: $languageCode")
        }
    }

    /**
     * Save an adapter binary [bytes] to internal storage under [adapterId].
     * Called after downloading from the backend.
     */
    fun saveAdapterFile(adapterId: String, bytes: ByteArray, isUser: Boolean = false, userId: String? = null): File {
        val filename = if (isUser && userId != null) "user_${userId}.bin" else "${adapterId}.bin"
        val file = File(adaptersDir, filename)
        file.writeBytes(bytes)
        Log.i(TAG, "Saved adapter '$adapterId' → ${file.absolutePath}")
        return file
    }

    override fun currentStackedAdapters(): List<AdapterHandle> = stackedAdapters.toList()

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
