package com.vaanimitra.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Native clarification UI for low-confidence transcripts and confirmation gates.
 */
class ClarificationActivity : Activity() {

    companion object {
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_OPTION_A = "option_a"
        const val EXTRA_OPTION_B = "option_b"
        const val CHOICE_A = "a"

        @Volatile
        var pendingCallback: ((String) -> Unit)? = null

        suspend fun requestChoice(context: Context, prompt: String, options: List<String>): String? =
            suspendCancellableCoroutine { cont ->
                pendingCallback = { choice ->
                    pendingCallback = null
                    if (cont.isActive) cont.resume(choice)
                }
                val intent = Intent(context, ClarificationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_PROMPT, prompt)
                    putExtra(EXTRA_OPTION_A, options.getOrElse(0) { "Yes" })
                    putExtra(EXTRA_OPTION_B, options.getOrElse(1) { "Cancel" })
                }
                context.startActivity(intent)
                cont.invokeOnCancellation { pendingCallback = null }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: "Choose"
        val optA = intent.getStringExtra(EXTRA_OPTION_A) ?: "Yes"
        val optB = intent.getStringExtra(EXTRA_OPTION_B) ?: "Cancel"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply {
            text = prompt
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = optA
            setOnClickListener {
                pendingCallback?.invoke(optA)
                pendingCallback = null
                finish()
            }
        })
        layout.addView(Button(this).apply {
            text = optB
            setOnClickListener {
                pendingCallback?.invoke(optB)
                pendingCallback = null
                finish()
            }
        })
        setContentView(layout)
    }
}
