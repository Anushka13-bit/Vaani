package com.vaanimitra.audio

import android.util.Log

/**
 * VoiceActivityDetector — detects speech vs. silence to trim audio before STT.
 *
 * Stub implementation: uses simple energy-based VAD.
 * For production: integrate Silero VAD (ONNX) or WebRTC VAD (via JNI/NDK).
 *
 * TODO: Replace energy threshold with Silero VAD model inference.
 */
class VoiceActivityDetector(
    private val energyThreshold: Float = 300f,  // tune per device
    private val sampleRate: Int = 16_000,
) {

    companion object {
        private const val TAG = "VoiceActivityDetector"
        private const val FRAME_DURATION_MS = 30  // VAD frame size
    }

    /**
     * Returns true if [frame] is likely speech (non-silent).
     */
    fun isSpeech(frame: ShortArray): Boolean {
        val rms = rmsEnergy(frame)
        val result = rms > energyThreshold
        Log.v(TAG, "RMS=${"%.1f".format(rms)} → ${if (result) "SPEECH" else "SILENCE"}")
        return result
    }

    /**
     * True if any frame in [pcm] carries speech energy.
     *
     * trimSilence cannot answer this: when no frame is speech it leaves speechStart/
     * speechEnd at the full range and hands back the untouched buffer, so a capture of
     * pure silence is never empty and slips past an isEmpty() guard into a full ONNX
     * encoder+decoder pass.
     */
    fun hasSpeech(pcm: ShortArray): Boolean {
        val frameSize = sampleRate * FRAME_DURATION_MS / 1000
        if (pcm.size < frameSize) return false
        var i = 0
        while (i + frameSize <= pcm.size) {
            if (rmsEnergy(pcm.copyOfRange(i, i + frameSize)) > energyThreshold) return true
            i += frameSize
        }
        return false
    }

    /**
     * Trim leading and trailing silence from a full PCM buffer.
     * Returns a sub-array containing only the speech region.
     */
    fun trimSilence(pcm: ShortArray): ShortArray {
        val frameSize = sampleRate * FRAME_DURATION_MS / 1000

        var speechStart = 0
        var speechEnd = pcm.size

        // Find start
        var i = 0
        while (i + frameSize <= pcm.size) {
            if (isSpeech(pcm.copyOfRange(i, i + frameSize))) {
                speechStart = i
                break
            }
            i += frameSize
        }

        // Find end
        var j = pcm.size - frameSize
        while (j >= speechStart) {
            if (isSpeech(pcm.copyOfRange(j, j + frameSize))) {
                speechEnd = j + frameSize
                break
            }
            j -= frameSize
        }

        return if (speechStart < speechEnd) pcm.copyOfRange(speechStart, speechEnd)
        else pcm  // no silence found — return original
    }

    private fun rmsEnergy(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in frame) sumSq += s.toDouble() * s.toDouble()
        return Math.sqrt(sumSq / frame.size).toFloat()
    }
}
