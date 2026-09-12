package com.friday.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import java.util.concurrent.atomic.AtomicBoolean

class FridayVoiceInteractionSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private lateinit var voice: VoiceManager
    private lateinit var tts: TTSManager
    private lateinit var launcher: AppLauncher
    private lateinit var processor: FridayCommandProcessor
    private lateinit var agent: FridayAgent
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanedUp = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        launcher = AppLauncher(appContext)
        processor = FridayCommandProcessor()
        agent = FridayAgent(appContext)
        tts = TTSManager(appContext) {}
        voice = VoiceManager(appContext, object : VoiceManager.Listener {
            override fun onListening() { tts.speak("Yes Boss.") }
            override fun onResult(text: String) { handle(text) }
            override fun onError(message: String) { tts.speak(message); finish() }
        })
    }

    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Manual assistant invocations also need exclusive microphone ownership.
        FridayWakeCoordinator.pauseForSpeech()
        voice.start()
    }

    private fun handle(text: String) {
        val local = processor.process(text)
        if (local.handledLocally) {
            if (local.action != null) {
                if (launcher.launch(local.action)) tts.speak(local.text)
                else tts.speak("I couldn't complete that action on this phone, Boss.")
            } else tts.speak(local.text)
            finish()
            return
        }
        agent.handle(text) { answer, _ ->
            tts.speak(answer)
            finish()
        }
    }

    override fun onHide() {
        cleanupAndResumeWake()
        super.onHide()
    }

    override fun onDestroy() {
        cleanupAndResumeWake()
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun cleanupAndResumeWake() {
        if (!cleanedUp.compareAndSet(false, true)) return
        try { voice.destroy() } catch (_: Exception) {}
        // Give the system speech service a short window to release its input device
        // before the local ONNX AudioRecord is opened again.
        mainHandler.postDelayed({ FridayWakeCoordinator.resumeAfterSpeech() }, 250L)
    }
}
