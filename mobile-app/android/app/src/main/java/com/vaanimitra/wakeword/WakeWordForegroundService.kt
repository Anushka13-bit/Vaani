package com.vaanimitra.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.vaanimitra.MainActivity
import com.vaanimitra.R
import com.vaanimitra.pipeline.VoicePipeline
import com.vaanimitra.pipeline.VoiceSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground wake-word listener using **openWakeWord** (ONNX, no API key, Apache 2.0).
 *
 * Required assets (see scripts/download_wakeword_models.sh):
 *   - melspectrogram.onnx
 *   - embedding_model.onnx
 *   - hey_lily.onnx  (custom) OR hey_jarvis_v0.1.onnx (dev fallback)
 */
class WakeWordForegroundService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "vaani_wake_word"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.vaanimitra.wakeword.START"
        const val ACTION_STOP = "com.vaanimitra.wakeword.STOP"

        @Volatile
        var isRunning = false
    }

    private var wakeWordEngine: WakeWordEngine? = null
    private val sessionController = VoiceSessionController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var detectionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startListening()
                return START_STICKY
            }
        }
    }

    private fun startListening() {
        if (wakeWordEngine != null) return

        val modelPath = resolveWakeWordModel() ?: run {
            Log.e(
                TAG,
                "No wake word model in assets. Run: ./scripts/download_wakeword_models.sh",
            )
            stopSelf()
            return
        }

        if (!hasAsset("melspectrogram.onnx") || !hasAsset("embedding_model.onnx")) {
            Log.e(TAG, "Missing melspectrogram.onnx or embedding_model.onnx in assets")
            stopSelf()
            return
        }

        try {
            val model = WakeWordModel(
                name = "Hey Lily",
                modelPath = modelPath,
                threshold = 0.08f,
            )
            val engine = WakeWordEngine(
                context = applicationContext,
                models = listOf(model),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2000L,
            )
            wakeWordEngine = engine

            detectionJob = scope.launch {
                engine.detections.collect { detection ->
                    Log.i(TAG, "Wake word detected: ${detection.model.name} score=${detection.score}")
                    onWakeWordDetected()
                }
            }

            engine.start()
            isRunning = true
            Log.i(TAG, "openWakeWord listening (CPU/ONNX) model=$modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "openWakeWord init failed: ${e.message}", e)
            stopSelf()
        }
    }

    /** Prefer custom hey_lily.onnx; fall back to hey_jarvis for dev until trained. */
    private fun resolveWakeWordModel(): String? = when {
        hasAsset("hey_lily.onnx") -> "hey_lily.onnx"
        hasAsset("hey_jarvis_v0.1.onnx") -> {
            Log.w(TAG, "hey_lily.onnx not found — using hey_jarvis_v0.1.onnx. Train Hey Lily with openWakeWord.")
            "hey_jarvis_v0.1.onnx"
        }
        else -> null
    }

    private fun hasAsset(name: String): Boolean = try {
        assets.open(name).close()
        true
    } catch (_: Exception) {
        false
    }

    private fun onWakeWordDetected() {
        if (!sessionController.tryAcquire()) {
            Log.d(TAG, "Debounced — session already active")
            return
        }
        vibrateAck()
        VoicePipeline.runCommandSession(applicationContext) {
            sessionController.release()
        }
    }

    private fun vibrateAck() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib?.vibrate(120)
        }
    }

    private fun stopListening() {
        detectionJob?.cancel()
        detectionJob = null
        try {
            wakeWordEngine?.stop()
            wakeWordEngine?.release()
        } catch (_: Exception) {
        }
        wakeWordEngine = null
        isRunning = false
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "VaaniMitra Voice", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Listening for \"Hey Lily\"" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VaaniMitra listening")
            .setContentText("Say \"Hey Lily\" to give a command")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
