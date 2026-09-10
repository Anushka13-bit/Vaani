package com.vaanimitra.stt

import android.content.Context
import android.util.Log
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
        return handle
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
