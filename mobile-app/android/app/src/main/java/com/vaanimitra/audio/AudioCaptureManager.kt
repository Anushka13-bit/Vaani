package com.vaanimitra.audio
import java.io.File
import java.io.FileOutputStream

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

        /**
         * Write 16kHz mono 16-bit PCM bytes to a standard 44-byte RIFF/WAVE file.
         */
        fun writeWavFile(pcmBytes: ByteArray, destFile: File) {
            val totalDataLen = pcmBytes.size + 36
            val byteRate = SAMPLE_RATE * 1 * 2 // 16000 * channels * bytesPerSample = 32000

            java.io.FileOutputStream(destFile).use { out ->
                // RIFF chunk descriptor
                out.write("RIFF".toByteArray())
                out.write(intToByteArray(totalDataLen))
                out.write("WAVE".toByteArray())

                // "fmt " sub-chunk
                out.write("fmt ".toByteArray())
                out.write(intToByteArray(16))               // Subchunk1Size = 16 for PCM
                out.write(shortToByteArray(1))              // AudioFormat = 1 (PCM)
                out.write(shortToByteArray(1))              // NumChannels = 1 (mono)
                out.write(intToByteArray(SAMPLE_RATE))     // SampleRate = 16000
                out.write(intToByteArray(byteRate))        // ByteRate = 32000
                out.write(shortToByteArray(2))              // BlockAlign = channels * bytesPerSample = 2
                out.write(shortToByteArray(16))             // BitsPerSample = 16

                // "data" sub-chunk
                out.write("data".toByteArray())
                out.write(intToByteArray(pcmBytes.size))
                out.write(pcmBytes)
            }
        }

        private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

        private fun shortToByteArray(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )
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

    private val calibrationPcmBuffer = java.io.ByteArrayOutputStream()
    @Volatile
    private var isCalibrationRecording = false
    private var calibrationRecordThread: Thread? = null

    /**
     * Start capturing 16kHz mono 16-bit PCM directly from AudioRecord into memory.
     */
    @SuppressLint("MissingPermission")
    fun startCalibrationRecording() {
        if (isCalibrationRecording) return
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            .coerceAtLeast(SAMPLE_RATE * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize,
        )
        audioRecord = record
        calibrationPcmBuffer.reset()
        isCalibrationRecording = true
        record.startRecording()
        Log.d(TAG, "Calibration recording started at ${SAMPLE_RATE}Hz mono 16-bit PCM")

        calibrationRecordThread = Thread {
            val chunk = ByteArray(bufferSize)
            try {
                while (isCalibrationRecording) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        synchronized(calibrationPcmBuffer) {
                            calibrationPcmBuffer.write(chunk, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in calibration record thread: ${e.message}")
            } finally {
                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    /**
     * Stop capturing and save the recorded 16kHz mono 16-bit PCM audio directly to a standard WAV file.
     * App-private destination file should be: files/calibration/{session_id}/phrase_{NN}.wav
     */
    fun stopCalibrationRecordingAndSaveWav(destFile: File): Long {
        isCalibrationRecording = false
        try {
            calibrationRecordThread?.join(2000)
        } catch (_: Exception) {}
        calibrationRecordThread = null
        audioRecord = null

        val pcmBytes = synchronized(calibrationPcmBuffer) {
            calibrationPcmBuffer.toByteArray()
        }
        destFile.parentFile?.mkdirs()
        writeWavFile(pcmBytes, destFile)
        Log.i(TAG, "Saved 16kHz mono 16-bit PCM calibration clip: ${destFile.absolutePath} (${pcmBytes.size} bytes)")
        return destFile.length()
    }

    fun isCalibrationRecordingActive(): Boolean = isCalibrationRecording

    fun stopStreaming() {
        isCapturing = false
    }
}
