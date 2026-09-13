package com.vaanimitra.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.vaanimitra.util.PermissionHelper

/**
 * Restarts hands-free wake-word listening after a reboot, the way a system voice
 * assistant (e.g. "Hey Siri") is available again without the user having to reopen
 * the app first. RN/JS is not running yet when this fires, so it can only act on the
 * native-side flag WakeWordForegroundService itself maintains
 * ([WakeWordForegroundService.shouldRunOnBoot]) — the RN `wakeWordEnabled` setting in
 * AsyncStorage is not reachable from here.
 */
class WakeWordBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeWordBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!WakeWordForegroundService.shouldRunOnBoot(context)) {
            Log.i(TAG, "Wake word was off before shutdown — not restarting")
            return
        }
        if (!PermissionHelper.hasVoicePermissions(context)) {
            Log.w(TAG, "Wake word was on before shutdown, but voice permissions are missing — cannot restart")
            return
        }

        Log.i(TAG, "Restarting wake-word listening after boot")
        val serviceIntent = Intent(context, WakeWordForegroundService::class.java).apply {
            action = WakeWordForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
