package com.vaanimitra.bridge

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.vaanimitra.VaaniMitraComponents
import com.vaanimitra.nlu.PhrasebookSync
import com.vaanimitra.stt.AdapterDownloader
import com.vaanimitra.stt.ModelBundleManager
import com.vaanimitra.stt.OnnxRuntimeHolder
import com.vaanimitra.wakeword.WakeWordForegroundService
import android.content.Intent
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log
import org.json.JSONObject

@ReactModule(name = SpeechModule.NAME)
class SpeechModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "SpeechModule"
        private const val TAG = "SpeechModule"
    }

    override fun getName(): String = NAME

    private val adapterManager by lazy { VaaniMitraComponents.adapterManager(reactContext) }
    private val whisperEngine by lazy { VaaniMitraComponents.whisperEngine(reactContext) }
    private val phrasebookMatcher by lazy { VaaniMitraComponents.phrasebookMatcher() }
    private val scope = CoroutineScope(Dispatchers.Main)

    @ReactMethod
    fun ping(promise: Promise) {
        Log.d(TAG, "ping() called from RN")
        promise.resolve("pong")
    }

    @ReactMethod
    fun loadUserAdapter(userId: String, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val handle = adapterManager.loadUserAdapter(userId)
                whisperEngine.activeAdapterPath = handle.filePath
                promise.resolve(adapterHandleToMap(handle))
                Log.i(TAG, "loadUserAdapter resolved: ${handle.adapterId}")
            } catch (e: Exception) {
                Log.e(TAG, "loadUserAdapter failed: ${e.message}")
                promise.reject("ADAPTER_LOAD_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun loadLanguageAdapter(languageCode: String, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val handle = adapterManager.loadLanguageAdapter(languageCode)
                whisperEngine.activeAdapterPath = handle.filePath
                promise.resolve(adapterHandleToMap(handle))
            } catch (e: Exception) {
                promise.reject("ADAPTER_LOAD_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun downloadAndLoadClusterAdapter(
        mobileBundleUrl: String,
        authToken: String,
        languageCode: String,
        serverAdapterId: String,
        version: Int,
        promise: Promise,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = AdapterDownloader.downloadBytes(mobileBundleUrl, authToken)
                val bundleDir = ModelBundleManager.bundleDir(reactContext, serverAdapterId)
                val manifest = ModelBundleManager.extractZip(bytes, bundleDir)

                adapterManager.persistActiveConfig(
                    languageCode = languageCode,
                    clusterAdapterId = serverAdapterId,
                    clusterVersion = version,
                )
                val handle = adapterManager.loadLanguageAdapter(languageCode, serverAdapterId)
                whisperEngine.activeAdapterId = serverAdapterId
                whisperEngine.activeAdapterPath = bundleDir.absolutePath
                OnnxRuntimeHolder.release()

                val map = adapterHandleToMap(handle).apply {
                    putString("executionProvider", whisperEngine.executionProvider)
                    putString("bundlePath", bundleDir.absolutePath)
                    putBoolean("mergedLora", manifest.mergedLora)
                }
                promise.resolve(map)
                Log.i(TAG, "Mobile ONNX bundle loaded: $serverAdapterId")
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndLoadClusterAdapter failed: ${e.message}")
                promise.reject("ADAPTER_DOWNLOAD_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun startWakeWordService(promise: Promise) {
        try {
            val intent = Intent(reactContext, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("WAKE_WORD_START_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun stopWakeWordService(promise: Promise) {
        try {
            val intent = Intent(reactContext, WakeWordForegroundService::class.java).apply {
                action = WakeWordForegroundService.ACTION_STOP
            }
            reactContext.startService(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("WAKE_WORD_STOP_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun isWakeWordServiceRunning(promise: Promise) {
        promise.resolve(WakeWordForegroundService.isRunning)
    }

    @ReactMethod
    fun downloadAndLoadUserAdapter(
        downloadUrl: String,
        authToken: String,
        userId: String,
        version: Int,
        promise: Promise,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = AdapterDownloader.downloadBytes(downloadUrl, authToken)
                adapterManager.saveUserAdapterBytes(userId, bytes, version)
                val handle = adapterManager.loadUserAdapter(userId)
                whisperEngine.activeAdapterPath = handle.filePath
                promise.resolve(adapterHandleToMap(handle))
                Log.i(TAG, "User adapter loaded for $userId")
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndLoadUserAdapter failed: ${e.message}")
                promise.reject("ADAPTER_DOWNLOAD_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun restorePersistedAdapters(promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val handles = adapterManager.restorePersistedStack()
                whisperEngine.activeAdapterPath = handles.firstOrNull()?.filePath
                val array = Arguments.createArray()
                handles.forEach { array.pushMap(adapterHandleToMap(it)) }
                promise.resolve(array)
            } catch (e: Exception) {
                promise.reject("ADAPTER_RESTORE_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun transcribeFile(audioFilePath: String, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(audioFilePath)
                if (!file.exists()) {
                    promise.reject("FILE_NOT_FOUND", "Audio file not found: $audioFilePath")
                    return@launch
                }
                val bytes = file.readBytes()
                val shorts = ShortArray(bytes.size / 2)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(shorts)

                val result = whisperEngine.transcribe(shorts)
                val map = Arguments.createMap().apply {
                    putString("text", result.text)
                    putDouble("confidence", result.segments.firstOrNull()?.confidence?.toDouble() ?: 0.0)
                    putString("languageDetected", result.languageDetected)
                }
                promise.resolve(map)
            } catch (e: Exception) {
                Log.e(TAG, "transcribeFile failed: ${e.message}")
                promise.reject("TRANSCRIBE_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getCurrentAdapterInfo(promise: Promise) {
        scope.launch(Dispatchers.IO) {
            val adapters = adapterManager.currentStackedAdapters()
            val resolved = if (adapters.isEmpty()) {
                adapterManager.restorePersistedStack()
            } else {
                adapters
            }
            val array = Arguments.createArray()
            resolved.forEach { array.pushMap(adapterHandleToMap(it)) }
            promise.resolve(array)
        }
    }

    @ReactMethod
    fun syncPhrasebookEntry(entryJson: String, promise: Promise) {
        try {
            val entry = JSONObject(entryJson)
            val trigger = entry.optString("triggerPhrase", "").trim()
            if (trigger.isEmpty()) {
                promise.reject("PHRASEBOOK_SYNC_FAILED", "Missing triggerPhrase")
                return
            }
            val intent = PhrasebookSync.entryToIntent(entry)
            phrasebookMatcher.syncEntry(trigger, intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("PHRASEBOOK_SYNC_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun syncPhrasebookBulk(entriesJson: String, promise: Promise) {
        try {
            PhrasebookSync.syncBulk(phrasebookMatcher, entriesJson)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("PHRASEBOOK_SYNC_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun removePhrasebookEntry(triggerPhrase: String, promise: Promise) {
        phrasebookMatcher.removeEntry(triggerPhrase)
        promise.resolve(true)
    }

    @ReactMethod
    fun openVoiceInputSettings(promise: Promise) {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("SETTINGS_OPEN_FAILED", e.message, e)
        }
    }

    private fun adapterHandleToMap(handle: com.vaanimitra.stt.AdapterHandle): WritableMap =
        Arguments.createMap().apply {
            putString("adapterId", handle.adapterId)
            putInt("version", handle.version)
            putString("type", handle.type.name)
            putString("filePath", handle.filePath)
            putString("checksum", handle.checksum)
        }
}
