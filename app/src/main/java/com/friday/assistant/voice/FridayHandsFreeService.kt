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
        try {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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

        runCatching { tts = TTSManager(applicationContext) {
            FridayRuntime.update("TTS ERROR", "Android speech output is unavailable", false)
        }}.onFailure { FridayRuntime.update("TTS ERROR", "Android speech output could not be initialized", false) }
        runCatching { agent = FridayAgent(applicationContext) }.onFailure { FridayRuntime.update("BRAIN ERROR", "FRIDAY brain could not be initialized", false) }
        main.postDelayed({ if (!stopped) startListening() }, 1200L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!stopped && !busy) main.post { startListening() }
        return START_STICKY
    }

    private fun startListening() {
        if (stopped || busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for FRIDAY", false)
            scheduleRestart(2000L)
            return
        }
        busy = true
        runCatching { voice?.destroy() }
        voice = null
        try {
            val newVoice = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() { FridayRuntime.update("LISTENING", "FRIDAY is listening — speak naturally", true) }
                override fun onAmplitude(value: Float) = Unit
                override fun onResult(text: String) {
                    if (stopped) return
                    val clean = text.trim()
                    if (clean.isBlank()) { finishCycle(300L); return }
                    FridayRuntime.update("HEARD", clean, true)
                    val currentAgent = agent
                    if (currentAgent == null) { finishCycle(300L); return }
                    try {
                        currentAgent.handle(clean) { answer, _ ->
                            if (stopped) return@handle
                            main.post {
                                if (stopped) return@post
                                val spoken = answer.trim().ifBlank { "I didn't get a response, Boss." }
                                FridayRuntime.update("SPEAKING", spoken.take(240), true)
                                runCatching { tts?.speak(spoken) { finishCycle(300L) } ?: finishCycle(300L) }
                                    .onFailure { finishCycle(300L) }
                            }
                        }
                    } catch (_: Throwable) { finishCycle(300L) }
                }
                override fun onError(message: String) {
                    if (stopped) return
                    FridayRuntime.update("VOICE RETRY", message, true)
                    finishCycle(if (message.contains("permission", true)) 2000L else 700L)
                }
            })
            voice = newVoice
            newVoice.start()
        } catch (_: Throwable) {
            busy = false
            voice = null
            FridayRuntime.update("VOICE RETRY", "Android speech engine could not start", true)
            scheduleRestart(1200L)
        }
    }

    private fun finishCycle(delayMs: Long) {
        if (stopped) return
        runCatching { voice?.destroy() }
        voice = null
        busy = false
        scheduleRestart(delayMs)
    }

    private fun scheduleRestart(delayMs: Long) {
        val token = ++restartToken
        main.postAtTime({ if (!stopped && token == restartToken && !busy) startListening() }, LISTEN_TAG, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    override fun onDestroy() {
        stopped = true
        restartToken++
        main.removeCallbacksAndMessages(null)
        runCatching { voice?.destroy() }
        voice = null
        busy = false
        runCatching { agent?.close() }
        agent = null
        runCatching { tts?.shutdown() }
        tts = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
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
