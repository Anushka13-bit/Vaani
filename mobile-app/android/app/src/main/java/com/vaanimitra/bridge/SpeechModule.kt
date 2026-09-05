package com.vaanimitra.bridge

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.vaanimitra.stt.AdapterManager
import com.vaanimitra.stt.AdapterType
import com.vaanimitra.stt.WhisperInferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log

/**
 * SpeechModule — @ReactModule exposing STT and adapter APIs to React Native (§2.2).
 *
 * This is the primary bridge between the RN JS layer and the native STT engine.
 * All methods are @ReactMethod — called via promise from SpeechBridge.ts.
 *
 * Build risk note (§9): Get a trivial "ping" round-trip working first before
 * adding real STT logic. The ping() method below serves this purpose.
 */
@ReactModule(name = SpeechModule.NAME)
class SpeechModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "SpeechModule"
        private const val TAG = "SpeechModule"
    }

    override fun getName(): String = NAME

    private val adapterManager by lazy { AdapterManager(reactContext) }
    private val whisperEngine by lazy { WhisperInferenceEngine(reactContext) }
    private val scope = CoroutineScope(Dispatchers.Main)

    // ── Ping (bridge smoke test) ──────────────────────────────────────────────

    /**
     * Trivial round-trip for bridge validation.
     * In RN: SpeechModule.ping().then(r => console.log(r)) → "pong"
     */
    @ReactMethod
    fun ping(promise: Promise) {
        Log.d(TAG, "ping() called from RN")
        promise.resolve("pong")
    }

    // ── Adapter loading ───────────────────────────────────────────────────────

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

    // ── File transcription (calibration review) ───────────────────────────────

    @ReactMethod
    fun transcribeFile(audioFilePath: String, promise: Promise) {
        scope.launch(Dispatchers.IO) {
            try {
                // Load PCM from file
                // TODO: Use MediaExtractor or ffmpeg-android to decode audio files.
                // For now, load raw .pcm files (16kHz mono int16).
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

    // ── Adapter info ──────────────────────────────────────────────────────────

    @ReactMethod
    fun getCurrentAdapterInfo(promise: Promise) {
        val adapters = adapterManager.currentStackedAdapters()
        val array = Arguments.createArray()
        adapters.forEach { array.pushMap(adapterHandleToMap(it)) }
        promise.resolve(array)
    }

    // ── Phrasebook mirror sync ────────────────────────────────────────────────

    @ReactMethod
    fun syncPhrasebookEntry(entryJson: String, promise: Promise) {
        // TODO: Deserialize and push to PhrasebookMatcher native cache
        Log.d(TAG, "syncPhrasebookEntry: $entryJson")
        promise.resolve(true)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun adapterHandleToMap(handle: com.vaanimitra.stt.AdapterHandle): WritableMap =
        Arguments.createMap().apply {
            putString("adapterId", handle.adapterId)
            putInt("version", handle.version)
            putString("type", handle.type.name)
            putString("filePath", handle.filePath)
            putString("checksum", handle.checksum)
        }
}
