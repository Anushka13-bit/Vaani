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
import com.vaanimitra.bridge.RecognitionEventEmitter
import com.vaanimitra.pipeline.VoicePipeline
import com.vaanimitra.pipeline.VoiceSessionController
import com.vaanimitra.util.PermissionHelper
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
 *
 * Mic contention resolution:
 *   The WakeWordEngine holds AudioRecord continuously. When a detection fires, we call
 *   engine.stop() to release the hardware mic, hand control to VoicePipeline, then
 *   call engine.start() again in the completion callback. This prevents a second
 *   AudioRecord from colliding on the same VOICE_RECOGNITION audio source.
 */
class WakeWordForegroundService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        /**
         * openWakeWord scores 0..1. This sat at 0.08, which fired on ordinary room noise
         * roughly every cooldown window and ran a full NPU transcription on silence each
         * time. Raised conservatively rather than to the usual ~0.5, because a miss on
         * stage is worse than an extra trigger and dysarthric speech can score lower.
         * The detection log line prints the real score — tune against it.
         */
        private const val WAKE_THRESHOLD = 0.20f
        private const val CHANNEL_ID = "vaani_wake_word"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.vaanimitra.wakeword.START"
        const val ACTION_STOP  = "com.vaanimitra.wakeword.STOP"

        @Volatile var isRunning = false

        /** Exposed so SpeechModule can surface it to React Native. */
        @Volatile var lastStopReason: String = ""
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
                lastStopReason = ""
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

        if (!PermissionHelper.hasVoicePermissions(this)) {
            val reason = "Voice permissions not granted (RECORD_AUDIO or POST_NOTIFICATIONS)"
            Log.e(TAG, reason)
            lastStopReason = reason
            RecognitionEventEmitter.instance?.emitWakeWordError(reason)
            stopSelf()
            return
        }

        val modelPath = resolveWakeWordModel() ?: run {
            val reason = "No wake word model in assets. Run: ./scripts/download_wakeword_models.sh"
            Log.e(TAG, reason)
            lastStopReason = reason
            RecognitionEventEmitter.instance?.emitWakeWordError(reason)
            stopSelf()
            return
        }

        if (!hasAsset("melspectrogram.onnx") || !hasAsset("embedding_model.onnx")) {
            val reason = "Missing melspectrogram.onnx or embedding_model.onnx in assets"
            Log.e(TAG, reason)
            lastStopReason = reason
            RecognitionEventEmitter.instance?.emitWakeWordError(reason)
            stopSelf()
            return
        }

        try {
            val modelName = if (modelPath.contains("lily", ignoreCase = true)) "Hey Lily" else "Hey Jarvis"
            val model = WakeWordModel(
                name = modelName,
                modelPath = modelPath,
                threshold = WAKE_THRESHOLD,
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
                    onWakeWordDetected(detection.model.name, detection.score)
                }
            }

            engine.start()
            isRunning = true
            lastStopReason = ""
            Log.i(TAG, "openWakeWord listening (CPU/ONNX) model=$modelPath")
        } catch (e: Exception) {
            val reason = "openWakeWord init failed: ${e.message}"
            Log.e(TAG, reason, e)
            lastStopReason = reason
            RecognitionEventEmitter.instance?.emitWakeWordError(reason)
            stopSelf()
        }
    }

    /** Prefer custom hey_lily.onnx; fall back to hey_jarvis for dev until trained. */
    private fun resolveWakeWordModel(): String? = when {
        hasAsset("hey_lily.onnx") -> "hey_lily.onnx"
        hasAsset("hey_jarvis_v0.1.onnx") -> {
            Log.w(TAG, "hey_lily.onnx not found — using hey_jarvis_v0.1.onnx as dev fallback")
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

    private fun onWakeWordDetected(modelName: String, score: Float) {
        if (!sessionController.tryAcquire()) {
            Log.d(TAG, "Debounced — command session already active")
            return
        }

        // Emit event to React Native (listening indicator, analytics)
        RecognitionEventEmitter.instance?.emitWakeWordDetected(modelName, score)

        vibrateAck()

        // ── Mic Contention Resolution ────────────────────────────────────────
        // Stop the wake engine to release the AudioRecord hardware channel.
        // VoicePipeline.runCommandSession() will open its own AudioRecord while the
        // engine is paused. On completion, we restart the engine.
        val engine = wakeWordEngine
        try {
            engine?.stop()
            Log.d(TAG, "WakeWordEngine paused — mic handed to VoicePipeline")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause engine before command session: ${e.message}")
        }

        VoicePipeline.runCommandSession(applicationContext) {
            // Called on completion (timeout, silence-end, success, or error)
            sessionController.release()
            if (isRunning) {
                try {
                    engine?.start()
                    Log.i(TAG, "WakeWordEngine resumed after command session")
                } catch (e: Exception) {
                    val reason = "Failed to resume WakeWordEngine: ${e.message}"
                    Log.e(TAG, reason, e)
                    lastStopReason = reason
                    RecognitionEventEmitter.instance?.emitWakeWordError(reason)
                }
            }
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
        val modelLabel = if (resolveWakeWordModel()?.contains("lily", ignoreCase = true) == true) "Hey Lily" else "Hey Jarvis"
        val channel = NotificationChannel(
            CHANNEL_ID, "VaaniMitra Voice", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Listening for \"$modelLabel\"" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VaaniMitra listening")
            .setContentText("Say \"$modelLabel\" to give a command")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
