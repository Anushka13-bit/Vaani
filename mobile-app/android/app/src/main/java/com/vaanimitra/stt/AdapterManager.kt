package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * AdapterManager — loads, stores, and stacks LoRA adapter files (§2.3).
 */

enum class AdapterType { USER, LANGUAGE, CLUSTER }

data class AdapterHandle(
    val adapterId: String,
    val version: Int,
    val type: AdapterType,
    val filePath: String,
    val checksum: String,
)

interface AdapterManagerInterface {
    suspend fun loadUserAdapter(userId: String): AdapterHandle
    suspend fun loadLanguageAdapter(languageCode: String, serverAdapterId: String? = null): AdapterHandle
    fun currentStackedAdapters(): List<AdapterHandle>
}

class AdapterManager(private val context: Context) : AdapterManagerInterface {

    companion object {
        private const val TAG = "AdapterManager"
        private const val ADAPTERS_DIR = "lora_adapters"
        private const val PREFS_NAME = "vaani_adapters"
        private const val KEY_LANGUAGE = "active_language"
        private const val KEY_USER_ID = "active_user_id"
        private const val KEY_CLUSTER_ID = "active_cluster_id"
        private const val KEY_CLUSTER_VERSION = "active_cluster_version"
    }

    private val adaptersDir: File by lazy {
        File(context.filesDir, ADAPTERS_DIR).also { it.mkdirs() }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val stackedAdapters = mutableListOf<AdapterHandle>()

    override suspend fun loadUserAdapter(userId: String): AdapterHandle {
        val file = File(adaptersDir, "user_${userId}.bin")
        if (!file.exists()) {
            Log.w(TAG, "USER adapter not found for userId=$userId. Download first.")
            throw IllegalStateException("User adapter not found for userId=$userId. Run calibration first.")
        }
        val handle = AdapterHandle(
            adapterId = "user_$userId",
            version = prefs.getInt("user_${userId}_version", 1),
            type = AdapterType.USER,
            filePath = file.absolutePath,
            checksum = sha256(file),
        )
        stackedAdapters.removeAll { it.type == AdapterType.USER }
        stackedAdapters.add(handle)
        persistActiveConfig(userId = userId)
        Log.i(TAG, "Loaded USER adapter: ${handle.adapterId}")
        return handle
    }

    override suspend fun loadLanguageAdapter(
        languageCode: String,
        serverAdapterId: String? = null,
    ): AdapterHandle {
        val file = File(adaptersDir, languageFileName(languageCode))
        if (!file.exists()) {
            Log.w(TAG, "Language adapter not found for lang=$languageCode")
            throw IllegalStateException("Language adapter not found: $languageCode")
        }

        val clusterId = serverAdapterId ?: prefs.getString(KEY_CLUSTER_ID, null) ?: "lang_$languageCode"
        val version = prefs.getInt(KEY_CLUSTER_VERSION, 1)
        val type = if (serverAdapterId != null || clusterId.startsWith("torgo_") || clusterId.contains("cluster")) {
            AdapterType.CLUSTER
        } else {
            AdapterType.LANGUAGE
        }

        val handle = AdapterHandle(
            adapterId = clusterId,
            version = version,
            type = type,
            filePath = file.absolutePath,
            checksum = sha256(file),
        )
        stackedAdapters.removeAll { it.type == AdapterType.LANGUAGE || it.type == AdapterType.CLUSTER }
        stackedAdapters.add(handle)
        persistActiveConfig(
            languageCode = languageCode,
            userId = null,
            clusterAdapterId = clusterId,
            clusterVersion = version,
        )
        Log.i(TAG, "Loaded ${type.name} adapter: ${handle.adapterId} (lang=$languageCode)")
        return handle
    }

    fun saveLanguageAdapterBytes(languageCode: String, bytes: ByteArray): File {
        val file = File(adaptersDir, languageFileName(languageCode))
        file.writeBytes(bytes)
        Log.i(TAG, "Saved language adapter for '$languageCode' → ${file.absolutePath}")
        return file
    }

    fun saveUserAdapterBytes(userId: String, bytes: ByteArray, version: Int = 1): File {
        val file = File(adaptersDir, "user_${userId}.bin")
        file.writeBytes(bytes)
        prefs.edit().putInt("user_${userId}_version", version).apply()
        Log.i(TAG, "Saved user adapter for '$userId' → ${file.absolutePath}")
        return file
    }

    fun persistActiveConfig(
        languageCode: String? = null,
        userId: String? = null,
        clusterAdapterId: String? = null,
        clusterVersion: Int? = null,
    ) {
        prefs.edit().apply {
            if (languageCode != null) putString(KEY_LANGUAGE, languageCode)
            if (userId != null) putString(KEY_USER_ID, userId)
            if (clusterAdapterId != null) putString(KEY_CLUSTER_ID, clusterAdapterId)
            if (clusterVersion != null) putInt(KEY_CLUSTER_VERSION, clusterVersion)
        }.apply()
    }

    fun restorePersistedStack(): List<AdapterHandle> {
        stackedAdapters.clear()
        val handles = mutableListOf<AdapterHandle>()

        val language = prefs.getString(KEY_LANGUAGE, null)
        val clusterId = prefs.getString(KEY_CLUSTER_ID, null)
        if (language != null) {
            try {
                handles.add(loadLanguageAdapter(language, clusterId))
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore language adapter: ${e.message}")
            }
        }

        val userId = prefs.getString(KEY_USER_ID, null)
        if (userId != null) {
            try {
                handles.add(loadUserAdapter(userId))
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore user adapter: ${e.message}")
            }
        }

        return handles
    }

    override fun currentStackedAdapters(): List<AdapterHandle> = stackedAdapters.toList()

    private fun languageFileName(languageCode: String): String = "lang_${languageCode}.bin"

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
