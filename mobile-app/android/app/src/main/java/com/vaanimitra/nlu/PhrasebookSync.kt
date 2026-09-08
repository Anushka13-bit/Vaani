package com.vaanimitra.nlu

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts phrasebook JSON (from RN) into native ParsedIntent objects.
 */
object PhrasebookSync {

    private const val TAG = "PhrasebookSync"

    fun entryToIntent(entryJson: JSONObject): ParsedIntent {
        val actionStr = entryJson.optString("actionType", "DICTATE_TEXT")
        val action = try {
            ActionType.valueOf(actionStr)
        } catch (_: Exception) {
            ActionType.DICTATE_TEXT
        }

        val payload = try {
            JSONObject(entryJson.optString("actionPayloadJson", "{}"))
        } catch (_: Exception) {
            JSONObject()
        }

        val entities = mutableMapOf<String, String>()
        when (action) {
            ActionType.PLACE_CALL -> entities["contact"] = payload.optString("contact", "")
            ActionType.SEND_MESSAGE -> {
                entities["contact"] = payload.optString("contact", "")
                entities["body"] = payload.optString("body", "")
            }
            ActionType.WEB_SEARCH -> entities["query"] = payload.optString("query", "")
            ActionType.OPEN_APP -> entities["app"] = payload.optString("app", "")
            ActionType.SET_ALARM -> entities["time"] = payload.optString("time", "")
            ActionType.SET_REMINDER -> entities["label"] = payload.optString("label", "")
            ActionType.DICTATE_TEXT -> entities["text"] = payload.optString("text", entryJson.optString("triggerPhrase", ""))
            else -> entities["text"] = payload.optString("text", entryJson.optString("triggerPhrase", ""))
        }

        val requiresConfirmation = action == ActionType.PLACE_CALL ||
            action == ActionType.SEND_MESSAGE

        return ParsedIntent(
            action = action,
            entities = entities,
            confidence = 0.95f,
            requiresConfirmation = requiresConfirmation,
        )
    }

    fun syncBulk(phrasebookMatcher: PhrasebookMatcher, entriesJson: String) {
        phrasebookMatcher.clearAll()
        try {
            val array = JSONArray(entriesJson)
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val trigger = entry.optString("triggerPhrase", "").trim()
                if (trigger.isEmpty()) continue
                val intent = entryToIntent(entry)
                phrasebookMatcher.syncEntry(trigger, intent)
            }
            Log.i(TAG, "Synced ${array.length()} phrasebook entries")
        } catch (e: Exception) {
            Log.e(TAG, "syncBulk failed: ${e.message}")
            throw e
        }
    }
}
