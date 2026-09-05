package com.vaanimitra.nlu

import android.util.Log

/**
 * VaaniMitra — NLU data classes and IntentParser (§2.3)
 */

enum class ActionType {
    DICTATE_TEXT, SEND_MESSAGE, SET_REMINDER, SET_ALARM,
    PLACE_CALL, WEB_SEARCH, OPEN_APP, SMART_HOME_ACTION, UNKNOWN
}

data class ParsedIntent(
    val action: ActionType,
    val entities: Map<String, String>,
    val confidence: Float,
    val requiresConfirmation: Boolean,
)

interface IntentParserInterface {
    fun parse(transcript: String): ParsedIntent
}

/**
 * IntentParser — maps a raw transcript to a ParsedIntent.
 *
 * Stub implementation: rule-based keyword matching.
 * TODO: Replace with a small on-device NLU model (e.g., MiniLM) or integrate
 *       with the system assistant via RecognitionService results extras.
 */
class IntentParser : IntentParserInterface {

    companion object {
        private const val TAG = "IntentParser"
    }

    override fun parse(transcript: String): ParsedIntent {
        val lower = transcript.lowercase().trim()
        Log.d(TAG, "Parsing: '$lower'")

        return when {
            matchesAny(lower, "send", "message", "text") -> ParsedIntent(
                action = ActionType.SEND_MESSAGE,
                entities = extractMessageEntities(lower),
                confidence = 0.75f,
                requiresConfirmation = true,   // irreversible
            )
            matchesAny(lower, "call", "dial", "phone") -> ParsedIntent(
                action = ActionType.PLACE_CALL,
                entities = extractContactEntity(lower),
                confidence = 0.80f,
                requiresConfirmation = true,
            )
            matchesAny(lower, "remind", "reminder") -> ParsedIntent(
                action = ActionType.SET_REMINDER,
                entities = mapOf("label" to lower),
                confidence = 0.70f,
                requiresConfirmation = false,
            )
            matchesAny(lower, "alarm", "wake me") -> ParsedIntent(
                action = ActionType.SET_ALARM,
                entities = extractTimeEntity(lower),
                confidence = 0.72f,
                requiresConfirmation = false,
            )
            matchesAny(lower, "search", "google", "look up") -> ParsedIntent(
                action = ActionType.WEB_SEARCH,
                entities = mapOf("query" to lower.substringAfter("search").trim()),
                confidence = 0.78f,
                requiresConfirmation = false,
            )
            matchesAny(lower, "open", "launch", "start app") -> ParsedIntent(
                action = ActionType.OPEN_APP,
                entities = mapOf("app" to lower.substringAfter("open").trim()),
                confidence = 0.70f,
                requiresConfirmation = false,
            )
            lower.isNotBlank() -> ParsedIntent(
                action = ActionType.DICTATE_TEXT,
                entities = mapOf("text" to transcript),
                confidence = 0.90f,
                requiresConfirmation = false,
            )
            else -> ParsedIntent(
                action = ActionType.UNKNOWN,
                entities = emptyMap(),
                confidence = 0.0f,
                requiresConfirmation = false,
            )
        }
    }

    private fun matchesAny(text: String, vararg keywords: String) =
        keywords.any { text.contains(it) }

    private fun extractMessageEntities(text: String): Map<String, String> {
        // e.g. "send message to Ravi saying running late"
        val contact = Regex("to ([A-Za-z]+)").find(text)?.groupValues?.get(1) ?: "unknown"
        val body = Regex("saying (.+)").find(text)?.groupValues?.get(1) ?: text
        return mapOf("contact" to contact, "body" to body)
    }

    private fun extractContactEntity(text: String): Map<String, String> {
        val contact = Regex("(?:call|dial|phone) ([A-Za-z]+)").find(text)?.groupValues?.get(1) ?: "unknown"
        return mapOf("contact" to contact)
    }

    private fun extractTimeEntity(text: String): Map<String, String> {
        val time = Regex("\\b(\\d{1,2}(?::\\d{2})? ?(?:am|pm)?)\\b").find(text)?.value ?: "unknown"
        return mapOf("time" to time)
    }
}
