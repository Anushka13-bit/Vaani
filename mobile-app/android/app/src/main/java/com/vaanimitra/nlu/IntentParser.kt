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
 * Matching is phrase-based rather than substring-based. Substring matching produced
 * false positives that were impossible to debug from a transcript alone ("in this
 * context" contains "text"; "telephone" contains "phone"), and first-match-wins
 * ordering meant an early rule silently shadowed a better later one. Here every rule
 * is tried and the longest matched trigger wins, so "send a message" beats "send"
 * and specific phrasings beat generic ones.
 */
class IntentParser : IntentParserInterface {

    companion object {
        private const val TAG = "IntentParser"

        /**
         * Phrases that look like commands but aren't. Without these, "let's call it a
         * day" dials a contact named "it".
         */
        private val IDIOMS = listOf(
            "call it a day", "call it quits", "call it even", "close call",
            "text book", "textbook", "no call",
        )

        /** Fillers a speaker puts before the real command. */
        private val LEAD_INS = listOf(
            "can you", "could you", "please", "i want to", "i want you to",
            "i need to", "i'd like to", "would you", "let's", "lets", "go ahead and",
        )

        /**
         * Android blocks apps from toggling wifi/bluetooth directly (wifi since 10,
         * bluetooth needs user consent), so "turn on bluetooth" opens that settings
         * panel instead of silently failing. Honest behaviour beats a dead intent —
         * which is why SMART_HOME_ACTION is still never produced.
         */
        private val DEVICE_CONTROL = listOf(
            "turn on", "turn off", "switch on", "switch off", "enable", "disable",
        )
    }

    private data class Rule(
        val action: ActionType,
        val triggers: List<String>,
        val confidence: Float,
        val requiresConfirmation: Boolean,
        /** (normalisedText, matchedTrigger) -> entities */
        val extract: (String, String) -> Map<String, String>,
    )

    private val rules: List<Rule> = listOf(
        Rule(
            action = ActionType.SEND_MESSAGE,
            triggers = listOf(
                "send a message", "send a text", "send message", "send text",
                "write to", "message", "text", "sms", "whatsapp", "drop a line",
                "shoot a text", "send",
            ),
            confidence = 0.75f,
            requiresConfirmation = true,
            extract = { text, _ -> messageEntities(text) },
        ),
        Rule(
            action = ActionType.PLACE_CALL,
            triggers = listOf(
                "make a call to", "give a call to", "place a call to", "video call",
                "call up", "call", "dial", "ring", "phone",
            ),
            confidence = 0.80f,
            requiresConfirmation = true,
            extract = { text, trigger -> mapOf("contact" to remainderAfter(text, trigger)) },
        ),
        Rule(
            action = ActionType.SET_ALARM,
            triggers = listOf(
                "set an alarm", "set alarm", "wake me up", "wake me", "alarm",
                "get me up",
            ),
            confidence = 0.75f,
            requiresConfirmation = false,
            extract = { text, _ -> mapOf("time" to extractTime(text)) },
        ),
        Rule(
            action = ActionType.SET_REMINDER,
            triggers = listOf(
                "set a timer", "set timer", "remind me to", "remind me", "reminder",
                "remind", "timer", "don't let me forget", "dont let me forget",
            ),
            confidence = 0.72f,
            requiresConfirmation = false,
            extract = { text, trigger ->
                mapOf("label" to remainderAfter(text, trigger).ifBlank { text })
            },
        ),
        Rule(
            action = ActionType.WEB_SEARCH,
            triggers = listOf(
                "search for", "look up", "google", "search", "find out about",
                "tell me about", "what is", "what are", "who is", "how do i", "how to",
            ),
            confidence = 0.78f,
            requiresConfirmation = false,
            extract = { text, trigger ->
                // A question is its own query; a command's query is what follows it.
                val q = if (trigger.startsWith("what") || trigger.startsWith("who") ||
                    trigger.startsWith("how")
                ) text else remainderAfter(text, trigger)
                mapOf("query" to q.ifBlank { text })
            },
        ),
        Rule(
            action = ActionType.OPEN_APP,
            triggers = DEVICE_CONTROL + listOf(
                "open up", "open", "launch", "start", "fire up", "bring up",
                "pull up", "go to", "switch to", "show me",
            ),
            confidence = 0.70f,
            requiresConfirmation = false,
            extract = { text, trigger -> mapOf("app" to stripAppNoun(remainderAfter(text, trigger))) },
        ),
    )

    override fun parse(transcript: String): ParsedIntent {
        val norm = normalize(transcript)
        Log.d(TAG, "Parsing: '$norm'")

        if (norm.isBlank()) {
            return ParsedIntent(ActionType.UNKNOWN, emptyMap(), 0f, false)
        }

        IDIOMS.firstOrNull { norm.contains(it) }?.let {
            Log.d(TAG, "Idiom '$it' — treating as dictation, not a command")
            return dictation(transcript)
        }

        // Earliest trigger wins, longest breaks ties. These commands are imperatives, so
        // the verb leads: in "launch whatsapp" the app name is an argument, and ranking on
        // length alone would let it outrank the verb and route this to SEND_MESSAGE.
        var best: Rule? = null
        var bestTrigger = ""
        var bestPos = Int.MAX_VALUE
        for (rule in rules) {
            for (trigger in rule.triggers) {
                val pos = phrasePosition(norm, trigger) ?: continue
                val better = pos < bestPos || (pos == bestPos && trigger.length > bestTrigger.length)
                if (better) {
                    best = rule
                    bestTrigger = trigger
                    bestPos = pos
                }
            }
        }

        if (best == null) {
            Log.d(TAG, "No command trigger matched — dictation")
            return dictation(transcript)
        }

        val entities = best.extract(norm, bestTrigger)
        Log.d(TAG, "Matched '$bestTrigger' -> ${best.action} entities=$entities")
        return ParsedIntent(best.action, entities, best.confidence, best.requiresConfirmation)
    }

    private fun dictation(original: String) =
        ParsedIntent(ActionType.DICTATE_TEXT, mapOf("text" to original), 0.90f, false)

    /** Lowercase, drop punctuation, strip lead-in fillers, collapse whitespace. */
    private fun normalize(raw: String): String {
        var s = raw.lowercase().replace(Regex("[^a-z0-9:' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (lead in LEAD_INS) {
                if (s.startsWith("$lead ")) {
                    s = s.removePrefix("$lead ").trim()
                    changed = true
                }
            }
        }
        return s
    }

    /** Start index of a word-boundary phrase match, or null. "context" is not "text". */
    private fun phrasePosition(text: String, phrase: String): Int? {
        val m = Regex("(^| )" + Regex.escape(phrase) + "($| )").find(text) ?: return null
        return text.indexOf(phrase, m.range.first)
    }

    /** "open the netflix app" -> "netflix"; launcher labels never carry the noun. */
    private fun stripAppNoun(raw: String): String {
        var s = raw
        for (suffix in listOf(" app", " application", " screen")) {
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trim()
        }
        return s
    }

    /** Text following the trigger, minus connecting words the label never contains. */
    private fun remainderAfter(text: String, trigger: String): String {
        val match = Regex("(^| )" + Regex.escape(trigger) + "($| )").find(text)
            ?: return text
        var rest = text.substring(match.range.last + 1).trim()
        for (filler in listOf("to ", "for ", "the ", "my ", "a ", "on ", "app ")) {
            if (rest.startsWith(filler)) rest = rest.removePrefix(filler).trim()
        }
        return rest
    }

    private fun messageEntities(text: String): Map<String, String> {
        val contact = Regex("\\bto ([a-z]+)").find(text)?.groupValues?.get(1) ?: "unknown"
        val body = Regex("\\b(?:saying|that|about) (.+)").find(text)?.groupValues?.get(1)
            ?: text
        return mapOf("contact" to contact, "body" to body)
    }

    private fun extractTime(text: String): String =
        Regex("\\b(\\d{1,2}(?::\\d{2})? ?(?:am|pm)?)\\b").find(text)?.value?.trim()
            ?: "unknown"
}
