package com.vaanimitra.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import com.vaanimitra.nlu.ParsedIntent

/**
 * AndroidIntentActions — fires standard Android Intents for SMS, dial, alarm, search, app-open.
 * These do NOT require AccessibilityService — they use standard Android Intent APIs.
 */
class AndroidIntentActions(private val context: Context) {

    companion object {
        private const val TAG = "AndroidIntentActions"
    }

    fun sendSms(contact: String, body: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$contact")
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "SMS intent fired: to=$contact")
            ActionResult(success = true, message = "Opening SMS to $contact")
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed: ${e.message}")
            ActionResult(success = false, message = "Failed to open SMS: ${e.message}",
                requiresAccessibilityFallback = true)
        }
    }

    fun placeCall(contact: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$contact")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Call intent fired: $contact")
            ActionResult(success = true, message = "Opening dialler for $contact")
        } catch (e: Exception) {
            Log.e(TAG, "placeCall failed: ${e.message}")
            ActionResult(success = false, message = "Failed to open dialler: ${e.message}")
        }
    }

    fun setAlarm(time: String): ActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, "VaaniMitra alarm")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Alarm intent fired: time=$time")
            ActionResult(success = true, message = "Setting alarm for $time")
        } catch (e: Exception) {
            Log.e(TAG, "setAlarm failed: ${e.message}")
            ActionResult(success = false, message = "Failed to set alarm: ${e.message}")
        }
    }

    fun setReminder(label: String): ActionResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Reminder intent fired: label=$label")
            ActionResult(success = true, message = "Reminder set: $label")
        } catch (e: Exception) {
            Log.e(TAG, "setReminder failed: ${e.message}")
            ActionResult(success = false, message = "Failed to set reminder: ${e.message}")
        }
    }

    fun webSearch(query: String): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Web search intent: query=$query")
            ActionResult(success = true, message = "Searching for $query")
        } catch (e: Exception) {
            Log.e(TAG, "webSearch failed: ${e.message}")
            ActionResult(success = false, message = "Failed to search: ${e.message}")
        }
    }

    fun openApp(appName: String): ActionResult {
        return try {
            val packageManager = context.packageManager
            val packages = packageManager.getInstalledApplications(0)
            val match = packages.firstOrNull {
                packageManager.getApplicationLabel(it).toString()
                    .lowercase().contains(appName.lowercase())
            }
            if (match != null) {
                val launchIntent = packageManager.getLaunchIntentForPackage(match.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    ActionResult(success = true, message = "Opening ${match.packageName}")
                } else {
                    ActionResult(success = false, message = "No launch intent for $appName",
                        requiresAccessibilityFallback = true)
                }
            } else {
                ActionResult(success = false, message = "App '$appName' not found",
                    requiresAccessibilityFallback = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openApp failed: ${e.message}")
            ActionResult(success = false, message = "Failed to open app: ${e.message}")
        }
    }
}
