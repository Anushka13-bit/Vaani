package com.vaanimitra.stt

import android.util.Log
import kotlin.math.exp

/**
 * ConfidenceScorer — extracts per-segment confidence from Whisper outputs.
 *
 * Real implementation: Whisper's decoder produces log-probability sequences.
 * Confidence for a segment = exp(avg(log_probs for all tokens in segment)).
 *
 * This stub derives a heuristic confidence from text length + character diversity
 * until real log-probs are wired from the ONNX decoder.
 *
 * TODO: Accept actual token log-probs from WhisperInferenceEngine and compute
 *       segment confidence as exp(mean(log_probs)).
 */
class ConfidenceScorer {

    companion object {
        private const val TAG = "ConfidenceScorer"
        const val LOW_CONFIDENCE_THRESHOLD = 0.6f
    }

    /**
     * Given a list of segments, annotate each with a confidence score.
     * [tokenLogProbs] is null in stub mode; when ONNX is wired, pass the
     * actual per-token log probabilities from the decoder.
     */
    fun score(
        segments: List<TranscriptSegment>,
        tokenLogProbs: List<Float>? = null,
    ): List<TranscriptSegment> {
        return segments.map { segment ->
            val confidence = if (tokenLogProbs != null) {
                // Real path: exp(mean(log_probs))
                val mean = tokenLogProbs.average().toFloat()
                exp(mean).coerceIn(0f, 1f)
            } else {
                // Stub heuristic
                heuristicConfidence(segment.text)
            }
            Log.v(TAG, "Segment '${segment.text.take(20)}…' → confidence=${"%.2f".format(confidence)}")
            segment.copy(confidence = confidence)
        }
    }

    /**
     * Returns true if [segment] should be flagged for clarification.
     */
    fun isLowConfidence(segment: TranscriptSegment): Boolean =
        segment.confidence < LOW_CONFIDENCE_THRESHOLD

    private fun heuristicConfidence(text: String): Float {
        // Stub: short or empty text → low confidence; longer → higher
        if (text.isBlank()) return 0.1f
        val wordCount = text.trim().split("\\s+".toRegex()).size
        return (0.5f + (wordCount.toFloat() / 20f)).coerceIn(0f, 1f)
    }
}
