package com.friday.assistant.voice

import android.content.Context
import android.service.voice.VoiceInteractionSession
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.voice.TTSManager

class FridayVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private lateinit var voice: VoiceManager
    private lateinit var tts: TTSManager
    private lateinit var launcher: AppLauncher
    private lateinit var processor: FridayCommandProcessor
    private lateinit var agent: FridayAgent

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        launcher = AppLauncher(context)
        processor = FridayCommandProcessor()
        agent = FridayAgent(context)
        tts = TTSManager(context) {}
        voice = VoiceManager(context, object : VoiceManager.Listener {
            override fun onListening() { tts.speak("Yes Boss.") }
            override fun onResult(text: String) { handle(text) }
            override fun onError(message: String) { tts.speak(message); finish() }
        })
    }

    override fun onShow(args: android.os.Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        voice.start()
    }

    private fun handle(text: String) {
        val local = processor.process(text)
        if (local.handledLocally) {
            if (local.action != null) {
                if (launcher.launch(local.action)) tts.speak(local.text)
                else tts.speak("I couldn't complete that action on this phone, Boss.")
            } else {
                tts.speak(local.text)
            }
            finish()
            return
        }
        agent.handle(text) { answer, _ ->
            tts.speak(answer)
            finish()
        }
    }

    override fun onHide() {
        try { voice.destroy() } catch (_: Exception) {}
        finish()
        super.onHide()
    }

    override fun onDestroy() {
        try { voice.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
