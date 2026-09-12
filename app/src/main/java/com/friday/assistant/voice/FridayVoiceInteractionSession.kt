package com.friday.assistant.voice

import android.content.Context
import android.os.Bundle
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
    private val sessionActive = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        launcher = AppLauncher(appContext)
        processor = FridayCommandProcessor()
        agent = FridayAgent(appContext)
        tts = TTSManager(appContext) {}
        voice = VoiceManager(appContext, object : VoiceManager.Listener {
            override fun onListening() = Unit
            override fun onResult(text: String) { handle(text) }
            override fun onError(message: String) {
                if (!sessionActive.get()) return
                voice.destroy()
                respond(message)
            }
        })
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        cleanedUp.set(false)
        sessionActive.set(true)
        FridayWakeCoordinator.pauseForSpeech()
        voice.start()
    }

    private fun handle(text: String) {
        if (!sessionActive.get()) return
        voice.destroy()
        val local = processor.process(text)
        if (local.handledLocally) {
            if (local.action != null) {
                if (launcher.launch(local.action)) respond(local.text)
                else respond("I couldn't complete that action on this phone, Boss.")
            } else respond(local.text)
            return
        }
        agent.handle(text) { answer, _ ->
            if (!sessionActive.get()) return@handle
            respond(answer)
        }
    }

    private fun respond(text: String) {
        if (!sessionActive.get()) return
        tts.speak(text) {
            mainHandler.post {
                if (sessionActive.compareAndSet(true, false)) finish()
            }
        }
    }

    override fun onHide() {
        sessionActive.set(false)
        cleanupAndResumeWake()
        super.onHide()
    }

    override fun onDestroy() {
        sessionActive.set(false)
        cleanupAndResumeWake()
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun cleanupAndResumeWake() {
        if (!cleanedUp.compareAndSet(false, true)) return
        try { voice.destroy() } catch (_: Exception) {}
        // SpeechRecognizer is destroyed above; wait briefly for the system audio service
        // to release the input device before reopening the local ONNX microphone.
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ FridayWakeCoordinator.resumeAfterSpeech() }, 250L)
    }
}
