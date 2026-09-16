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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.runtime.FridayRuntime

/** Persistent foreground voice engine. SpeechRecognizer is used as the reliable command path. */
class FridayHandsFreeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var voice: VoiceManager? = null
    private var tts: TTSManager? = null
    private var agent: FridayAgent? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false
    @Volatile private var busy = false
    private var restartToken = 0L

    override fun onCreate() {
        super.onCreate()
        stopped = false
        createChannel()
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            FridayRuntime.update("HANDS-FREE ERROR", "Android refused the microphone foreground service", false)
            stopSelf()
            return
        }

        runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FRIDAY:HandsFree"
            ).apply {
                setReferenceCounted(false)
                acquire()
                wakeLock = this
            }
        }

        tts = TTSManager(applicationContext) {
            FridayRuntime.update("TTS ERROR", "Android speech output is unavailable", false)
        }
        agent = FridayAgent(applicationContext)
        main.postDelayed({ if (!stopped) startListening() }, 500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!stopped && !busy) {
            main.removeCallbacksAndMessages(LISTEN_TAG)
            main.post { startListening() }
        }
        return START_STICKY
    }

    /**
     * Keep a live command listener instead of relying exclusively on a custom wake-word model.
     * The user can say "Hey Friday ..." or simply speak the command. Android ends each speech
     * recognition session after silence; we immediately create a fresh session.
     */
    private fun startListening() {
        if (stopped || busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for FRIDAY", false)
            scheduleRestart(1500L)
            return
        }
        busy = true
        voice?.destroy()
        voice = VoiceManager(applicationContext, object : VoiceManager.Listener {
            override fun onListening() {
                FridayRuntime.update("LISTENING", "FRIDAY is listening — speak naturally", true)
            }

            override fun onAmplitude(value: Float) {
                // VoiceManager already publishes amplitude to the live HUD state.
            }

            override fun onResult(text: String) {
                val clean = text.trim()
                if (stopped) return
                if (clean.isBlank()) {
                    finishCycle(250L)
                    return
                }
                FridayRuntime.update("HEARD", clean, true)
                agent?.handle(clean) { answer, _ ->
                    if (stopped) return@handle
                    main.post {
                        if (stopped) return@post
                        val spoken = answer.trim().ifBlank { "I didn't get a response, Boss." }
                        FridayRuntime.update("SPEAKING", spoken.take(240), true)
                        tts?.speak(spoken) { finishCycle(200L) } ?: finishCycle(200L)
                    }
                }
            }

            override fun onError(message: String) {
                if (stopped) return
                FridayRuntime.update("VOICE RETRY", message, true)
                finishCycle(if (message.contains("permission", true)) 1500L else 450L)
            }
        })
        voice?.start()
    }

    private fun finishCycle(delayMs: Long) {
        if (stopped) return
        voice?.destroy()
        voice = null
        busy = false
        scheduleRestart(delayMs)
    }

    private fun scheduleRestart(delayMs: Long) {
        val token = ++restartToken
        main.postAtTime({
            if (!stopped && token == restartToken && !busy) startListening()
        }, LISTEN_TAG, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    override fun onDestroy() {
        stopped = true
        restartToken++
        main.removeCallbacksAndMessages(null)
        voice?.destroy()
        voice = null
        busy = false
        agent?.close()
        agent = null
        tts?.shutdown()
        tts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FRIDAY Hands-Free", NotificationManager.IMPORTANCE_LOW).apply {
                description = "FRIDAY background voice assistant"
            }
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("FRIDAY hands-free active")
        .setContentText("Speak a command — no tap required.")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val CHANNEL_ID = "friday_hands_free"
        private const val NOTIFICATION_ID = 704
        private const val LISTEN_TAG = "friday-listen-restart"
    }
}
