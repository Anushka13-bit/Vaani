package com.vaanimitra.nlu

import android.util.Log

/**
 * PhrasebookMatcher — fast fuzzy match against user shortcuts (§3.3).
 *
 * The phrasebook is mirrored from the RN SQLite store into this in-memory cache at app start
 * (via SpeechModule.syncPhrasebookEntry), so matching happens natively without a bridge
 * round-trip per utterance.
 *
 * Match algorithm: Levenshtein edit distance (word-level) — threshold 0.8 similarity.
 * For production, consider a trigram index for large phrasebooks.
 */
class PhrasebookMatcher {

    companion object {
        private const val TAG = "PhrasebookMatcher"
        private const val SIMILARITY_THRESHOLD = 0.75f
    }

    // Lightweight phrasebook cache: triggerPhrase → ParsedIntent
    private val cache = mutableMapOf<String, ParsedIntent>()

    data class MatchResult(
        val matched: Boolean,
        val intent: ParsedIntent?,
        val score: Float,
    )

    /**
     * Try to match [transcript] against the phrasebook.
     * Returns a MatchResult with matched=true and the stored intent if found.
     */
    fun match(transcript: String): MatchResult {
        val normalised = transcript.lowercase().trim()
        var bestScore = 0f
        var bestIntent: ParsedIntent? = null

        for ((phrase, intent) in cache) {
            val score = similarity(normalised, phrase.lowercase())
            if (score > bestScore) {
                bestScore = score
                bestIntent = intent
            }
        }

        val matched = bestScore >= SIMILARITY_THRESHOLD
        Log.d(TAG, "match('${normalised.take(30)}') → matched=$matched score=${"%.2f".format(bestScore)}")
        return MatchResult(matched, if (matched) bestIntent else null, bestScore)
    }

    /**
     * Add or update an entry in the native phrasebook cache.
     * Called from SpeechModule.syncPhrasebookEntry() when RN edits a phrase.
     */
    fun syncEntry(triggerPhrase: String, intent: ParsedIntent) {
        cache[triggerPhrase] = intent
        Log.d(TAG, "Synced phrasebook entry: '$triggerPhrase' → ${intent.action}")
    }

    fun removeEntry(triggerPhrase: String) {
        cache.remove(triggerPhrase)
    }

    fun clearAll() = cache.clear()

    // ── Levenshtein similarity (word-level) ───────────────────────────────────

    private fun similarity(a: String, b: String): Float {
        val aWords = a.split("\\s+".toRegex())
        val bWords = b.split("\\s+".toRegex())
        val dist = levenshtein(aWords, bWords)
        val maxLen = maxOf(aWords.size, bWords.size).coerceAtLeast(1)
        return 1f - dist.toFloat() / maxLen
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) dp[i][0] = i
        for (j in 0..b.size) dp[0][j] = j
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.size][b.size]
    }
}
