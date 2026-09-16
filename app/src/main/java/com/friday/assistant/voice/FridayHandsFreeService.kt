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
import com.friday.assistant.R
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.runtime.FridayRuntime

/** Persistent foreground voice loop. It owns the microphone so HUD and background use one path. */
class FridayHandsFreeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wakeDetector: FridayWakeDetector? = null
    private var voice: VoiceManager? = null
    private var tts: TTSManager? = null
    private var agent: FridayAgent? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false
    @Volatile private var speaking = false

    override fun onCreate() {
        super.onCreate()
        stopped = false
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else startForeground(NOTIFICATION_ID, notification)
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
        tts = TTSManager(applicationContext) { FridayRuntime.update("TTS ERROR", "Android speech output is unavailable", false) }
        agent = FridayAgent(applicationContext)
        main.postDelayed({ if (!stopped) startWakeListening() }, 350L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!stopped && wakeDetector == null && !speaking) main.post { startWakeListening() }
        return START_STICKY
    }

    private fun startWakeListening() {
        if (stopped || speaking) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for hands-free FRIDAY", false)
            return
        }
        wakeDetector?.stop()
        wakeDetector = FridayWakeDetector(
            applicationContext,
            onWake = { confidence, _ -> onWakeDetected(confidence) },
            onStopped = { main.post { if (!stopped && !speaking && wakeDetector?.isRunning() != true) startWakeListening() } }
        ).also { it.start() }
        FridayRuntime.update("WAKE LISTENING", "Background microphone active • say Hey Friday", true)
    }

    private fun onWakeDetected(confidence: Float) {
        if (stopped || speaking) return
        FridayRuntime.update("WAKE ACCEPTED", "Hey Friday detected • ${"%.0f".format(confidence * 100f)}% confidence", true)
        speaking = true
        val detector = wakeDetector
        Thread {
            detector?.stopAndWait(1800L)
            main.post { if (!stopped) startCommandListening() else speaking = false }
        }.start()
    }

    private fun startCommandListening() {
        if (stopped) return
        voice?.destroy()
        voice = VoiceManager(applicationContext, object : VoiceManager.Listener {
            override fun onListening() {
                FridayRuntime.update("LISTENING", "Speak your command", true)
            }

            override fun onAmplitude(value: Float) = Unit

            override fun onResult(text: String) {
                if (stopped) return
                val clean = text.trim()
                if (clean.isBlank()) {
                    finishCommandCycle()
                    return
                }
                FridayRuntime.update("HEARD", clean, true)
                agent?.handle(clean) { answer, _ ->
                    if (stopped) return@handle
                    main.post {
                        if (stopped) return@post
                        FridayRuntime.update("SPEAKING", answer.take(240), true)
                        tts?.speak(answer) { finishCommandCycle() }
                    }
                }
            }

            override fun onError(message: String) {
                if (stopped) return
                FridayRuntime.update("VOICE ERROR", message, false)
                finishCommandCycle(delayMs = 700L)
            }
        })
        voice?.start()
    }

    private fun finishCommandCycle(delayMs: Long = 250L) {
        if (stopped) return
        voice?.destroy()
        voice = null
        speaking = false
        main.postDelayed({ if (!stopped) startWakeListening() }, delayMs)
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        wakeDetector?.stop()
        wakeDetector = null
        voice?.destroy()
        voice = null
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
        .setContentText("Say Hey Friday to give a command.")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val CHANNEL_ID = "friday_hands_free"
        private const val NOTIFICATION_ID = 704
    }
}
