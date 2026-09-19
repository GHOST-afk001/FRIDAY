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

/** Persistent hands-free FRIDAY foreground service. */
class FridayAlwaysOnService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var detector: FridayWakeDetector? = null
    private var voice: VoiceManager? = null
    private var tts: TTSManager? = null
    private var agent: FridayAgent? = null
    @Volatile private var destroyed = false
    @Volatile private var commandActive = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        createChannel()
        runCatching { tts = TTSManager(applicationContext) { FridayRuntime.update("TTS ERROR", "Speech output unavailable", false) } }
        runCatching { agent = FridayAgent(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for hands-free FRIDAY", false)
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification)
        }.onFailure {
            FridayRuntime.update("VOICE ERROR", "Android refused FRIDAY background microphone service", false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (detector?.isRunning() != true && !destroyed) startWake()
        return START_STICKY
    }

    private fun startWake() {
        if (destroyed || detector?.isRunning() == true || commandActive) return
        val newDetector = FridayWakeDetector(
            context = applicationContext,
            onWake = { confidence, _ ->
                if (destroyed || commandActive) return@FridayWakeDetector
                commandActive = true
                FridayRuntime.update("WAKE ACCEPTED", "Hey Friday detected • listening for your command", true)
                stopWake()
                main.post { startCommandListening(confidence) }
            },
            onAudioFocusLost = { permanent ->
                if (!destroyed && !commandActive) {
                    FridayRuntime.update("WAKE RECOVERING", "Microphone focus changed; restarting wake listener", true)
                    main.postDelayed({ if (!destroyed && !commandActive) startWake() }, if (permanent) 1200L else 700L)
                }
            },
            onStopped = {
                main.post {
                    if (!destroyed && !commandActive && detector?.isRunning() != true) {
                        main.postDelayed({ if (!destroyed && !commandActive) startWake() }, 500L)
                    }
                }
            }
        )
        detector = newDetector
        newDetector.start()
        FridayRuntime.update("WAKE LISTENING", "Hands-free active • say Hey Friday", true)
    }

    private fun stopWake() {
        detector?.let { runCatching { it.stop() } }
        detector = null
    }

    private fun startCommandListening(confidence: Float) {
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishCommand()
            return
        }
        runCatching { voice?.destroy() }
        voice = VoiceManager(applicationContext, object : VoiceManager.Listener {
            override fun onListening() { FridayRuntime.update("LISTENING", "FRIDAY is listening — speak your command", true) }
            override fun onAmplitude(value: Float) { com.friday.assistant.runtime.FridayStateFlow.updateAmplitude(value) }
            override fun onResult(text: String) {
                val clean = text.trim()
                if (clean.isBlank()) { speakAndResume("Boss, mujhe command sunai nahi di."); return }
                FridayRuntime.update("HEARD", clean, true)
                val currentAgent = agent ?: run { speakAndResume("Boss, FRIDAY brain abhi ready nahi hai."); return }
                runCatching {
                    currentAgent.handle(clean) { answer, _ -> main.post { if (!destroyed) speakAndResume(answer.trim().ifBlank { "Done, Boss." }) } }
                }.onFailure { speakAndResume("Boss, command process nahi ho paayi.") }
            }
            override fun onError(message: String) { if (!destroyed) speakAndResume(message) }
        })
        voice?.start()
    }

    private fun speakAndResume(text: String) {
        if (destroyed) return
        FridayRuntime.update("SPEAKING", text.take(240), true)
        runCatching { tts?.speak(text) { main.post { finishCommand() } } ?: finishCommand() }
            .onFailure { finishCommand() }
    }

    private fun finishCommand() {
        if (destroyed) return
        runCatching { voice?.destroy() }
        voice = null
        com.friday.assistant.runtime.FridayStateFlow.resetAmplitude()
        commandActive = false
        main.postDelayed({ if (!destroyed) startWake() }, 350L)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        runCatching { detector?.stop() }
        detector = null
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
            NotificationChannel(CHANNEL_ID, "FRIDAY Hands-Free", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps FRIDAY ready for the Hey Friday wake phrase"
            }
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("FRIDAY is active")
        .setContentText("Hands-free wake listening is ON")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val CHANNEL_ID = "friday_hands_free"
        private const val NOTIFICATION_ID = 705
        fun start(context: android.content.Context) = ContextCompat.startForegroundService(context, Intent(context, FridayAlwaysOnService::class.java))
        fun stop(context: android.content.Context) = context.stopService(Intent(context, FridayAlwaysOnService::class.java))
    }
}
