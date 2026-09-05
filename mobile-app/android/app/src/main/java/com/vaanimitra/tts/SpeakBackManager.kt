package com.vaanimitra.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * SpeakBackManager — wraps android.speech.tts.TextToSpeech for confirmation audio (§3.3).
 *
 * Used to read back a pending action to the user before they confirm it,
 * e.g.: "Sending message to Ravi: running late. Say yes or tap confirm."
 */
class SpeakBackManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "SpeakBackManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingQueue = mutableListOf<String>()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isReady = true
            Log.i(TAG, "TTS initialised")
            // Drain any pending utterances
            pendingQueue.forEach { speak(it) }
            pendingQueue.clear()
        } else {
            Log.e(TAG, "TTS initialisation failed: status=$status")
        }
    }

    /**
     * Speak [text] via system TTS.
     * If TTS is not yet ready, queues the utterance for playback after init.
     */
    fun speak(text: String) {
        if (!isReady) {
            pendingQueue.add(text)
            return
        }
        Log.d(TAG, "Speaking: '${text.take(60)}'")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vaani_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        Log.i(TAG, "TTS shut down")
    }
}
