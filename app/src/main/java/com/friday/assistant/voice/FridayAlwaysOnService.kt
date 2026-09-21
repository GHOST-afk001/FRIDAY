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
    // SpeechRecognizer fallback keeps hands-free wake working on devices where the bundled
    // native wake model cannot acquire AudioRecord reliably. It listens one utterance at a time.
    private var wakeVoice: VoiceManager? = null
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
        if (detector?.isRunning() != true && wakeVoice == null && !destroyed) startWake()
        return START_STICKY
    }

    private fun startWake() {
        if (destroyed || commandActive || wakeVoice != null) return

        // Do not keep AudioRecord and SpeechRecognizer open at the same time.
        // The speech wake path is the compatibility fallback for phones where the
        // bundled native Hey Friday engine cannot obtain the microphone reliably.
        FridayRuntime.update("WAKE LISTENING", "Hands-free active • say Friday or Baabu", true)

        wakeVoice = VoiceManager(applicationContext, object : VoiceManager.Listener {
            override fun onListening() {
                if (!destroyed && !commandActive) {
                    FridayRuntime.update("WAKE LISTENING", "Mic active • say Friday or Baabu", true)
                }
            }

            override fun onAmplitude(value: Float) {
                if (!destroyed && !commandActive) {
                    com.friday.assistant.runtime.FridayStateFlow.updateAmplitude(value)
                }
            }

            override fun onResult(text: String) {
                if (destroyed || commandActive) return
                val spoken = text.trim()
                val normalized = spoken.lowercase(java.util.Locale.ROOT)
                    .replace(Regex("[^a-z0-9\\s]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val wakePhrases = listOf("hey friday", "friday", "hey baabu", "baabu", "babu")
                val wakeIndex = wakePhrases
                    .map { phrase -> normalized.indexOf(phrase, 0, false) }
                    .filter { it >= 0 }
                    .minOrNull()

                if (wakeIndex == null) {
                    // SpeechRecognizer is one-shot; immediately arm the next short
                    // recognition window without requiring an orb tap.
                    restartWake(250L)
                    return
                }

                val wakeText = normalized.substring(wakeIndex)
                val command = wakeText
                    .removePrefix("hey friday").removePrefix("friday")
                    .removePrefix("hey baabu").removePrefix("baabu").removePrefix("babu").trim()

                commandActive = true
                FridayRuntime.update("WAKE ACCEPTED", "Hey Friday detected • listening for your command", true)
                stopWakeSpeech()

                if (command.isNotBlank()) {
                    handleCommand(command)
                } else {
                    main.post { startCommandListening(1f) }
                }
            }

            override fun onError(message: String) {
                if (destroyed || commandActive) return
                // Network/timeout/busy errors should not turn hands-free mode off.
                // Re-arm automatically after a short cooldown.
                restartWake(500L)
            }
        })
        wakeVoice?.start()
    }

    private fun restartWake(delayMs: Long) {
        if (destroyed || commandActive) return
        stopWakeSpeech()
        main.postDelayed({
            if (!destroyed && !commandActive) startWake()
        }, delayMs)
    }

    private fun stopWakeSpeech() {
        runCatching { wakeVoice?.destroy() }
        wakeVoice = null
        com.friday.assistant.runtime.FridayStateFlow.resetAmplitude()
    }

    private fun handleCommand(command: String) {
        if (destroyed) return
        val currentAgent = agent ?: run {
            speakAndResume("Boss, FRIDAY brain abhi ready nahi hai.")
            return
        }
        FridayRuntime.update("HEARD", command, true)
        runCatching {
            currentAgent.handle(command) { answer, _ ->
                main.post {
                    if (!destroyed) speakAndResume(answer.trim().ifBlank { "Done, Boss." })
                }
            }
        }.onFailure {
            speakAndResume("Boss, command process nahi ho paayi.")
        }
    }

    private fun stopWake() {
        val d = detector ?: return
        detector = null
        // Do not start SpeechRecognizer until AudioRecord has fully released the microphone.
        // Starting both back-to-back can make Samsung report a busy/failed recognizer.
        runCatching { d.stopAndWait(2500L) }
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
                description = "Keeps FRIDAY ready for the Friday or Baabu wake phrase"
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
