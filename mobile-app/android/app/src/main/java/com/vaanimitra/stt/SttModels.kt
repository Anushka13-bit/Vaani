package com.vaanimitra.stt

/**
 * VaaniMitra — Shared STT data classes (§2.3)
 */

data class TranscriptionResult(
    val text: String,
    val languageDetected: String,           // e.g. "en", "ta", "ta-en"
    val segments: List<TranscriptSegment>,
)

data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,                  // 0.0 – 1.0
)

interface SttEngine {
    /**
     * Runs inference using base Whisper + currently loaded LoRA adapter(s).
     * [pcmAudio] must be 16kHz mono int16 PCM.
     */
    suspend fun transcribe(pcmAudio: ShortArray, sampleRate: Int = 16_000): TranscriptionResult
}
