package com.vaanimitra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AudioCaptureManager — wraps Android AudioRecord to capture 16kHz mono PCM.
 * Required by WhisperInferenceEngine (Whisper expects 16kHz mono int16 input).
 *
 * Usage:
 *   val manager = AudioCaptureManager()
 *   val pcm: ShortArray = manager.captureSeconds(5)
 */
class AudioCaptureManager {

    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16_000        // Hz — Whisper requirement
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isCapturing = false

    /**
     * Capture [durationSeconds] of audio and return as a ShortArray (16kHz mono PCM).
     * Runs on IO dispatcher; call from a coroutine.
     */
    @SuppressLint("MissingPermission")   // Caller must hold RECORD_AUDIO permission
    suspend fun captureSeconds(durationSeconds: Int): ShortArray = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(SAMPLE_RATE * durationSeconds * 2) // 2 bytes per short

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize,
        )
        audioRecord = record

        val totalSamples = SAMPLE_RATE * durationSeconds
        val output = ShortArray(totalSamples)
        var offset = 0

        record.startRecording()
        isCapturing = true
        Log.d(TAG, "Recording started — ${durationSeconds}s at ${SAMPLE_RATE}Hz")

        try {
            while (offset < totalSamples && isCapturing) {
                val chunk = ShortArray(bufferSize / 2)
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    val copyLen = minOf(read, totalSamples - offset)
                    chunk.copyInto(output, offset, 0, copyLen)
                    offset += copyLen
                }
            }
        } finally {
            record.stop()
            record.release()
            audioRecord = null
            isCapturing = false
        }

        Log.d(TAG, "Recording complete — $offset samples captured")
        output
    }

    /**
     * Stream PCM via callback. Used by PersonalizedRecognitionService for live dictation.
     * Calls [onChunk] with each buffer until [stopStreaming] is called.
     */
    @SuppressLint("MissingPermission")
    suspend fun streamPcm(onChunk: (ShortArray) -> Unit) = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize,
        )
        audioRecord = record
        isCapturing = true
        record.startRecording()

        try {
            while (isCapturing) {
                val chunk = ShortArray(bufferSize / 2)
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) onChunk(chunk.copyOf(read))
            }
        } finally {
            record.stop()
            record.release()
            audioRecord = null
        }
    }

    fun stopStreaming() {
        isCapturing = false
    }
}
