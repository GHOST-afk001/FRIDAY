package com.friday.assistant.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.runtime.FridayRuntime

/**
 * Explicit one-shot fallback for the HUD orb.
 *
 * This service is intentionally NOT the always-on wake engine. The selected
 * VoiceInteractionService + FridayWakeCoordinator owns hands-free wake detection.
 */
class FridayHandsFreeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var voice: VoiceManager? = null
    private var tts: TTSManager? = null
    private var agent: FridayAgent? = null
    @Volatile private var stopped = false
    @Volatile private var commandStarted = false

    override fun onCreate() {
        super.onCreate()
        stopped = false
        createChannel()
        runCatching { tts = TTSManager(applicationContext) { FridayRuntime.update("TTS ERROR", "Android speech output is unavailable", false) } }
        runCatching { agent = FridayAgent(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            FridayRuntime.update("VOICE ERROR", "Android refused the microphone foreground service", false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for FRIDAY", false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // A second start while the first command is active is ignored rather than creating
        // competing SpeechRecognizer instances against the same microphone.
        if (!stopped && !commandStarted) main.post { startOneShotListening() }
        return START_NOT_STICKY
    }

    private fun startOneShotListening() {
        if (stopped || commandStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for FRIDAY", false)
            stopSelf()
            return
        }

        commandStarted = true
        runCatching { voice?.destroy() }
        voice = null
        try {
            val newVoice = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() {
                    FridayRuntime.update("LISTENING", "FRIDAY is listening — speak naturally", true)
                }

                override fun onAmplitude(value: Float) {
                    com.friday.assistant.runtime.FridayStateFlow.updateAmplitude(value)
                }

                override fun onResult(text: String) {
                    if (stopped) return
                    val clean = text.trim()
                    if (clean.isBlank()) {
                        finishAndStop(200L)
                        return
                    }
                    FridayRuntime.update("HEARD", clean, true)
                    val currentAgent = agent ?: run {
                        finishAndStop(200L)
                        return
                    }
                    runCatching {
                        currentAgent.handle(clean) { answer, _ ->
                            if (stopped) return@handle
                            main.post {
                                if (stopped) return@post
                                val spoken = answer.trim().ifBlank { "I didn't get a response, Boss." }
                                FridayRuntime.update("SPEAKING", spoken.take(240), true)
                                runCatching {
                                    tts?.speak(spoken) { finishAndStop(250L) } ?: finishAndStop(250L)
                                }.onFailure { finishAndStop(250L) }
                            }
                        }
                    }.onFailure { finishAndStop(250L) }
                }

                override fun onError(message: String) {
                    if (stopped) return
                    FridayRuntime.update("VOICE ERROR", message, false)
                    finishAndStop(300L)
                }
            })
            voice = newVoice
            newVoice.start()
        } catch (_: Throwable) {
            FridayRuntime.update("VOICE ERROR", "Android speech engine could not start", false)
            finishAndStop(250L)
        }
    }

    private fun finishAndStop(delayMs: Long) {
        if (stopped) return
        runCatching { voice?.destroy() }
        voice = null
        com.friday.assistant.runtime.FridayStateFlow.resetAmplitude()
        main.postDelayed({ if (!stopped) stopSelf() }, delayMs)
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        runCatching { voice?.destroy() }
        voice = null
        runCatching { agent?.close() }
        agent = null
        runCatching { tts?.shutdown() }
        tts = null
        com.friday.assistant.runtime.FridayStateFlow.resetAmplitude()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FRIDAY Voice", NotificationManager.IMPORTANCE_LOW).apply {
                description = "FRIDAY one-shot voice fallback"
            }
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("FRIDAY listening")
        .setContentText("Speak one command")
        .setOngoing(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val CHANNEL_ID = "friday_voice_fallback"
        private const val NOTIFICATION_ID = 704
    }
}
