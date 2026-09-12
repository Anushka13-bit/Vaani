package com.vaanimitra.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log

/**
 * AndroidIntentActions — fires standard Android Intents for SMS, dial, alarm, search, app-open.
 */
class AndroidIntentActions(private val context: Context) {

    companion object {
        private const val TAG = "AndroidIntentActions"

        private val SYSTEM_SCREENS = mapOf(
            "settings" to Settings.ACTION_SETTINGS,
            "setting" to Settings.ACTION_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "wi-fi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "date and time" to Settings.ACTION_DATE_SETTINGS,
            "airplane mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        )
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
        val query = normalizeAppQuery(appName)
        if (query.isBlank()) {
            return ActionResult(success = false, message = "No app name heard")
        }

        // System destinations are not launchable activities, so enumerating launcher
        // entries never finds them — "settings" has to go through its Settings intent.
        SYSTEM_SCREENS[query]?.let { action ->
            return try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i(TAG, "Opened system screen '$query' via $action")
                ActionResult(success = true, message = "Opening $query")
            } catch (e: Exception) {
                Log.e(TAG, "System screen '$query' failed: ${e.message}")
                ActionResult(success = false, message = "Could not open $query")
            }
        }

        return try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val candidates = pm.queryIntentActivities(launcherIntent, 0)
            if (candidates.isEmpty()) {
                // Almost always the missing <queries> manifest entry on API 30+.
                Log.w(TAG, "PackageManager returned no launchable apps — check <queries> visibility")
            }

            // Prefer an exact label, then a prefix, then a substring, so "clock" does not
            // lose to some "Clockwork Companion" that merely contains the word.
            val best = candidates
                .mapNotNull { info ->
                    val label = info.loadLabel(pm).toString().lowercase()
                    val rank = when {
                        label == query -> 0
                        label.startsWith(query) -> 1
                        label.contains(query) -> 2
                        else -> null
                    }
                    rank?.let { it to info }
                }
                .minByOrNull { it.first }
                ?.second

            if (best == null) {
                Log.w(TAG, "No installed app matches '$query' (${candidates.size} launchable apps visible)")
                return ActionResult(success = false, message = "I couldn't find an app called $query")
            }

            val pkg = best.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (launch == null) {
                Log.w(TAG, "No launch intent for $pkg")
                return ActionResult(
                    success = false,
                    message = "No launch intent for $query",
                    requiresAccessibilityFallback = true,
                )
            }
            context.startActivity(launch)
            Log.i(TAG, "Opened $pkg for query '$query'")
            ActionResult(success = true, message = "Opening $query")
        } catch (e: Exception) {
            Log.e(TAG, "openApp failed: ${e.message}")
            ActionResult(success = false, message = "Failed to open app: ${e.message}")
        }
    }

    /**
     * Whisper punctuates ("Open settings."), and people pad the name with articles and
     * a trailing noun ("open the Netflix app") that is never part of the launcher label.
     */
    private fun normalizeAppQuery(raw: String): String {
        var s = raw.lowercase().trim().trim('.', ',', '!', '?', ';', ':').trim()
        for (prefix in listOf("the ", "my ", "a ", "up ")) {
            if (s.startsWith(prefix)) s = s.removePrefix(prefix).trim()
        }
        for (suffix in listOf(" app", " application", " screen", " settings page")) {
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trim()
        }
        return s.trim('.', ',', '!', '?', ';', ':').trim()
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
