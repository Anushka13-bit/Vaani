package com.vaanimitra.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Log

/**
 * AndroidIntentActions — fires standard Android Intents for SMS, dial, alarm, search, app-open.
 */
class AndroidIntentActions(private val context: Context) {

    companion object {
        private const val TAG = "AndroidIntentActions"
    }

    fun sendSms(contact: String, body: String): ActionResult {
        return try {
            val phone = resolveContactPhone(contact) ?: contact
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "SMS intent fired: to=$phone")
            ActionResult(success = true, message = "Opening SMS to $contact")
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed: ${e.message}")
            ActionResult(
                success = false,
                message = "Failed to open SMS: ${e.message}",
                requiresAccessibilityFallback = true,
            )
        }
    }

    fun placeCall(contact: String): ActionResult {
        return try {
            val phone = resolveContactPhone(contact) ?: contact
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$phone")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Call intent fired: $contact → $phone")
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
                    ActionResult(
                        success = false,
                        message = "No launch intent for $appName",
                        requiresAccessibilityFallback = true,
                    )
                }
            } else {
                ActionResult(success = false, message = "App '$appName' not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openApp failed: ${e.message}")
            ActionResult(success = false, message = "Failed to open app: ${e.message}")
        }
    }

    private fun resolveContactPhone(contactName: String): String? {
        if (contactName.isBlank() || contactName == "unknown") return null
        if (contactName.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            return contactName.replace(" ", "")
        }

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)?.lowercase() ?: continue
                    if (name.contains(contactName.lowercase())) {
                        return cursor.getString(0)?.replace(Regex("\\s"), "")
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission not granted — using name as dial string")
            null
        }
    }
}
