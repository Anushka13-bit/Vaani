package com.vaanimitra.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Telephony
import android.util.Log
import com.vaanimitra.nlu.ActionType
import com.vaanimitra.nlu.ParsedIntent

/**
 * ActionResult — result of executing a ParsedIntent.
 */
data class ActionResult(
    val success: Boolean,
    val message: String,
    val requiresAccessibilityFallback: Boolean = false,
)

/**
 * ActionExecutor — dispatches a ParsedIntent to the appropriate handler (§2.3 / §3.3).
 */
class ActionExecutor(
    private val context: Context,
    private val intentActions: AndroidIntentActions,
) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    suspend fun execute(intent: ParsedIntent): ActionResult {
        Log.d(TAG, "Executing: ${intent.action} entities=${intent.entities}")
        return when (intent.action) {
            ActionType.SEND_MESSAGE -> intentActions.sendSms(
                contact = intent.entities["contact"] ?: "",
                body = intent.entities["body"] ?: "",
            )
            ActionType.PLACE_CALL -> intentActions.placeCall(
                contact = intent.entities["contact"] ?: "",
            )
            ActionType.SET_ALARM -> intentActions.setAlarm(
                time = intent.entities["time"] ?: "",
            )
            ActionType.SET_REMINDER -> intentActions.setReminder(
                label = intent.entities["label"] ?: "",
            )
            ActionType.WEB_SEARCH -> intentActions.webSearch(
                query = intent.entities["query"] ?: "",
            )
            ActionType.OPEN_APP -> intentActions.openApp(
                appName = intent.entities["app"] ?: "",
            )
            ActionType.DICTATE_TEXT -> ActionResult(
                success = true,
                message = intent.entities["text"] ?: "",
            )
            else -> ActionResult(
                success = false,
                message = "Unknown action: ${intent.action}",
                requiresAccessibilityFallback = false,
            )
        }
    }
}
