package com.friday.assistant.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayAction
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.security.ActionPolicyValidator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class FridayVoiceInteractionSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private lateinit var voice: VoiceManager
    private lateinit var tts: TTSManager
    private lateinit var launcher: AppLauncher
    private lateinit var processor: FridayCommandProcessor
    private lateinit var agent: FridayAgent
    private val policy = ActionPolicyValidator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanedUp = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private var pendingConfirmation: FridayAction? = null
    private var interactionGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        launcher = AppLauncher(appContext)
        processor = FridayCommandProcessor()
        tts = TTSManager(appContext) {}
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        cleanedUp.set(false)
        sessionActive.set(true)
        interactionGeneration++
        pendingConfirmation = null
        agent = FridayAgent(appContext)
        voice = createVoiceManager()
        FridayWakeCoordinator.pauseForSpeech()
        val fromWake = args?.containsKey("friday_wake_confidence") == true
        if (fromWake) {
            tts.speak("Yes Boss.") { mainHandler.post { startListeningIfCurrent() } }
        } else {
            voice.start()
        }
    }

    private fun createVoiceManager(): VoiceManager = VoiceManager(appContext, object : VoiceManager.Listener {
        override fun onListening() = Unit
        override fun onResult(text: String) { handle(text) }
        override fun onError(message: String) {
            if (!sessionActive.get() || cleanedUp.get()) return
            voice.destroy()
            respond(message, finish = true)
        }
    })

    private fun startListeningIfCurrent() {
        if (sessionActive.get() && !cleanedUp.get()) voice.start()
    }

    private fun handle(text: String) {
        if (!sessionActive.get() || cleanedUp.get()) return
        val currentGeneration = ++interactionGeneration
        voice.destroy()

        pendingConfirmation?.let { pending ->
            when (normalizeConfirmation(text)) {
                true -> {
                    pendingConfirmation = null
                    when (val outcome = policy.validate(pending)) {
                        is ActionPolicyValidator.Outcome.Approved -> execute(outcome.action)
                        is ActionPolicyValidator.Outcome.RequiresConfirmation -> execute(outcome.action)
                        is ActionPolicyValidator.Outcome.Rejected -> respond(outcome.reason, finish = true)
                    }
                }
                false -> {
                    pendingConfirmation = null
                    respond("Okay Boss, cancelled.", finish = true)
                }
                null -> {
                    respond("I didn't get a yes or no, Boss. The action is still waiting for confirmation.", finish = false)
                }
            }
            return
        }

        val local = processor.process(text)
        if (local.handledLocally) {
            val action = local.action
            if (action != null) {
                when (val outcome = policy.validate(action)) {
                    is ActionPolicyValidator.Outcome.Approved -> {
                        if (local.needsConfirmation) {
                            pendingConfirmation = outcome.action
                            scheduleConfirmationExpiry(currentGeneration)
                            respond("${local.text} Kya main ise kar doon, Boss?", finish = false)
                        } else {
                            execute(outcome.action, successText = local.text)
                        }
                    }
                    is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                        pendingConfirmation = outcome.action
                        scheduleConfirmationExpiry(currentGeneration)
                        respond("${local.text} ${outcome.prompt} Say yes or no, Boss.", finish = false)
                    }
                    is ActionPolicyValidator.Outcome.Rejected -> respond(outcome.reason, finish = true)
                }
            } else {
                respond(local.text, finish = true)
            }
            return
        }

        agent.handle(text) { answer, _ ->
            mainHandler.post {
                if (!sessionActive.get() || cleanedUp.get() || interactionGeneration != currentGeneration) return@post
                respond(answer, finish = true)
            }
        }
    }

    private fun execute(action: FridayAction, successText: String = "Done, Boss.") {
        if (launcher.launch(action)) respond(successText, finish = true)
        else respond("I couldn't complete that action on this phone, Boss.", finish = true)
    }

    private fun scheduleConfirmationExpiry(generation: Long) {
        mainHandler.postDelayed({
            if (sessionActive.get() && interactionGeneration == generation && pendingConfirmation != null) {
                pendingConfirmation = null
                respond("Confirmation timed out, Boss. I didn't perform the action.", finish = true)
            }
        }, 45_000L)
    }

    private fun normalizeConfirmation(text: String): Boolean? {
        val value = text.trim().lowercase(Locale.ROOT)
        if (value in setOf("yes", "haan", "ha", "han", "okay", "ok", "kar do", "do it", "sure")) return true
        if (value in setOf("no", "nahi", "nahin", "cancel", "mat karo", "don't", "nope")) return false
        return null
    }

    private fun respond(text: String, finish: Boolean) {
        if (!sessionActive.get()) return
        tts.speak(text) {
            mainHandler.post {
                if (!sessionActive.get()) return@post
                if (finish) {
                    sessionActive.set(false)
                    finish()
                } else if (pendingConfirmation != null) {
                    startListeningIfCurrent()
                }
            }
        }
    }

    override fun onHide() {
        sessionActive.set(false)
        pendingConfirmation = null
        cleanupAndResumeWake()
        super.onHide()
    }

    override fun onDestroy() {
        sessionActive.set(false)
        pendingConfirmation = null
        cleanupAndResumeWake()
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun cleanupAndResumeWake() {
        if (!cleanedUp.compareAndSet(false, true)) return
        if (::voice.isInitialized) runCatching { voice.destroy() }
        if (::agent.isInitialized) runCatching { agent.close() }
        interactionGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ FridayWakeCoordinator.resumeAfterSpeech() }, 100L)
    }
}
