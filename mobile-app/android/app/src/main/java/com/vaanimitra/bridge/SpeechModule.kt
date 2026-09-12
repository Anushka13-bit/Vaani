package com.vaanimitra.bridge

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.vaanimitra.VaaniMitraComponents
import com.vaanimitra.nlu.PhrasebookSync
import com.vaanimitra.stt.AdapterDownloader
import com.vaanimitra.stt.AdapterHandle
import com.vaanimitra.stt.AdapterType
import com.vaanimitra.stt.AdapterVerificationResult
import com.vaanimitra.stt.ModelBundleManager
import com.vaanimitra.stt.OnnxRuntimeHolder
import com.vaanimitra.util.PermissionHelper
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
        promise.resolve("pong")
    }

    @ReactMethod
    fun loadUserAdapter(userId: String, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val adapterId = adapterManager.getActiveOnnxAdapterId() ?: "user_$userId"
                activateOnnxBundle(adapterId)
                val handle = adapterManager.loadOnnxAdapter(adapterId, AdapterType.USER, 1)
                promise.resolve(adapterHandleToMap(handle))
            } catch (e: Exception) {
                promise.reject("ADAPTER_LOAD_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun loadLanguageAdapter(languageCode: String, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val adapterId = adapterManager.getActiveOnnxAdapterId()
                    ?: throw IllegalStateException("No ONNX adapter configured")
                activateOnnxBundle(adapterId)
                val handle = adapterManager.loadOnnxAdapter(adapterId, AdapterType.CLUSTER, 1)
                promise.resolve(adapterHandleToMap(handle))
            } catch (e: Exception) {
                promise.reject("ADAPTER_LOAD_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun downloadAndLoadMobileBundle(
        downloadUrl: String,
        authToken: String,
        adapterId: String,
        version: Int,
        adapterType: String,
        referenceAudioPath: String,
        promise: Promise,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val type = AdapterType.valueOf(adapterType.uppercase())
                val (handle, verification) = downloadExtractAndActivate(
                    downloadUrl, authToken, adapterId, version, type, referenceAudioPath,
                )
                promise.resolve(adapterHandleToMap(handle).apply {
                    putString("executionProvider", whisperEngine.executionProvider)
                    putString("bundlePath", handle.filePath)
                    putBoolean("mergedLora", true)
                    if (verification != null) {
                        putBoolean("verified", true)
                        putBoolean("transcriptChanged", verification.transcriptChanged)
                        putString("previousText", verification.previousText)
                        putString("newText", verification.newText)
                        putString("previousExecutionProvider", verification.previousExecutionProvider)
                        putString("newExecutionProvider", verification.newExecutionProvider)
                        putBoolean("usedNpuAfterSwap", verification.usedNpuAfterSwap)
                    } else {
                        putBoolean("verified", false)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndLoadMobileBundle failed: ${e.message}")
                promise.reject("ADAPTER_DOWNLOAD_FAILED", e.message, e)
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
                adapterManager.persistActiveConfig(
                    languageCode = languageCode,
                    clusterAdapterId = serverAdapterId,
                    clusterVersion = version,
                )
                val (handle, _) = downloadExtractAndActivate(
                    mobileBundleUrl, authToken, serverAdapterId, version, AdapterType.CLUSTER, "",
                )
                promise.resolve(adapterHandleToMap(handle))
            } catch (e: Exception) {
                promise.reject("ADAPTER_DOWNLOAD_FAILED", e.message, e)
            }
        }
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
                val adapterId = "user_$userId"
                adapterManager.persistActiveConfig(userId = userId, onnxAdapterId = adapterId)
                val (handle, _) = downloadExtractAndActivate(
                    downloadUrl, authToken, adapterId, version, AdapterType.USER, "",
                )
                promise.resolve(adapterHandleToMap(handle))
            } catch (e: Exception) {
                promise.reject("ADAPTER_DOWNLOAD_FAILED", e.message, e)
            }
        }
    }

    /**
     * Downloads and extracts the ONNX bundle, then activates it. When [referenceAudioPath]
     * is non-blank and [type] is USER, the swap runs through [AdapterManager.loadOnnxAdapterVerified]
     * so callers get a real before/after transcription comparison rather than a load that
     * could silently no-op.
     */
    private suspend fun downloadExtractAndActivate(
        url: String,
        authToken: String,
        adapterId: String,
        version: Int,
        type: AdapterType,
        referenceAudioPath: String,
    ): Pair<AdapterHandle, AdapterVerificationResult?> {
        val bytes = AdapterDownloader.downloadBytes(url, authToken)
        val bundleDir = ModelBundleManager.bundleDir(reactContext, adapterId)
        ModelBundleManager.extractZip(bytes, bundleDir)
        adapterManager.persistOnnxAdapter(adapterId, version, type)

        if (type == AdapterType.USER && referenceAudioPath.isNotBlank()) {
            val verification = adapterManager.loadOnnxAdapterVerified(adapterId, type, version, referenceAudioPath)
            val handle = adapterManager.currentStackedAdapters().lastOrNull { it.adapterId == adapterId }
                ?: AdapterHandle(adapterId, version, type, bundleDir.absolutePath, "")
            return handle to verification
        }

        activateOnnxBundle(adapterId)
        val handle = adapterManager.loadOnnxAdapter(adapterId, type, version)
        return handle to null
    }

    private fun activateOnnxBundle(adapterId: String) {
        whisperEngine.activeAdapterId = adapterId
        whisperEngine.activeAdapterPath = ModelBundleManager.bundleDir(reactContext, adapterId).absolutePath
        OnnxRuntimeHolder.release()
        Log.i(TAG, "Activated ONNX bundle: $adapterId")
    }

    @ReactMethod
    fun startWakeWordService(promise: Promise) {
        try {
            if (!PermissionHelper.hasVoicePermissions(reactContext)) {
                val reason = "Voice permissions not granted (RECORD_AUDIO or POST_NOTIFICATIONS)"
                WakeWordForegroundService.lastStopReason = reason
                RecognitionEventEmitter.instance?.emitWakeWordError(reason)
                promise.reject("PERMISSION_DENIED", reason)
                return
            }
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
    fun checkVoicePermissions(promise: Promise) {
        try {
            promise.resolve(PermissionHelper.hasVoicePermissions(reactContext))
        } catch (e: Exception) {
            promise.reject("PERMISSION_CHECK_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun getWakeWordStopReason(promise: Promise) {
        promise.resolve(WakeWordForegroundService.lastStopReason)
    }

    @ReactMethod
    fun restorePersistedAdapters(promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                val handles = adapterManager.restorePersistedStack()
                val onnxId = adapterManager.getActiveOnnxAdapterId()
                if (onnxId != null && ModelBundleManager.isBundleReady(reactContext, onnxId)) {
                    activateOnnxBundle(onnxId)
                }
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
                    putString("executionProvider", result.executionProvider)
                }
                promise.resolve(map)
            } catch (e: Exception) {
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

    private fun adapterHandleToMap(handle: AdapterHandle): WritableMap =
        Arguments.createMap().apply {
            putString("adapterId", handle.adapterId)
            putInt("version", handle.version)
            putString("type", handle.type.name)
            putString("filePath", handle.filePath)
            putString("checksum", handle.checksum)
        }
}
