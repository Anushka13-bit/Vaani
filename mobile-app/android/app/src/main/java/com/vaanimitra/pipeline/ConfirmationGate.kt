package com.vaanimitra.pipeline

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vaanimitra.nlu.ActionType
import com.vaanimitra.nlu.ParsedIntent
import com.vaanimitra.tts.SpeakBackManager
import com.vaanimitra.ui.ClarificationActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Speaks confirmation via TTS and blocks irreversible actions until user confirms.
 */
object ConfirmationGate {

    private const val TAG = "ConfirmationGate"

    suspend fun confirmIfNeeded(context: Context, intent: ParsedIntent): Boolean {
        if (!intent.requiresConfirmation) return true

        val message = when (intent.action) {
            ActionType.PLACE_CALL -> "Call ${intent.entities["contact"]}? Say yes to confirm."
            ActionType.SEND_MESSAGE -> "Send message to ${intent.entities["contact"]}? Say yes to confirm."
            else -> "Confirm action? Say yes."
        }

        val speakBack = SpeakBackManager(context.applicationContext)
        speakBack.speak(message)

        // Wait for TTS to finish (~length-based) then listen for "yes"
        kotlinx.coroutines.delay(2500)
        return awaitVerbalYes(context)
    }

    private suspend fun awaitVerbalYes(context: Context): Boolean =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            // Launch clarification activity for tap fallback
            // ClarificationActivity reports the button's label, not an id, so the
            // confirm branch has to compare against the very label we send. Comparing
            // to CHOICE_A ("a") was never equal to "Yes", so every confirmation was
            // silently answered "no" and the action cancelled even when the user tapped
            // Confirm.
            val confirmLabel = "Yes"
            val intent = Intent(context, ClarificationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ClarificationActivity.EXTRA_PROMPT, "Tap Confirm or say Yes")
                putExtra(ClarificationActivity.EXTRA_OPTION_A, confirmLabel)
                putExtra(ClarificationActivity.EXTRA_OPTION_B, "Cancel")
            }
            ClarificationActivity.pendingCallback = { choice ->
                val confirmed = choice.equals(confirmLabel, ignoreCase = true)
                Log.i(TAG, "Confirmation choice='$choice' -> confirmed=$confirmed")
                if (!done.getAndSet(true)) cont.resume(confirmed)
            }
            context.startActivity(intent)

            // Auto-timeout cancel after 8s
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!done.getAndSet(true)) {
                    Log.w(TAG, "Confirmation timed out after 8s with no choice — cancelling")
                    cont.resume(false)
                }
            }, 8000)
        }
}
