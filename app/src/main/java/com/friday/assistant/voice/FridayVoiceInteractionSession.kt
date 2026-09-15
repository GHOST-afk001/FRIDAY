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
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.security.ActionPolicyValidator
import com.friday.assistant.security.ActionResultValidator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class FridayVoiceInteractionSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private lateinit var voice: VoiceManager
    private lateinit var tts: TTSManager
    private lateinit var launcher: AppLauncher
    private lateinit var processor: FridayCommandProcessor
    private lateinit var agent: FridayAgent
    private val policy = ActionPolicyValidator()
    private val resultValidator = ActionResultValidator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cleanedUp = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private var pendingConfirmation: FridayAction? = null
    private var interactionGeneration = 0L
    private var confirmationGeneration = 0L

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
        clearPendingConfirmation()
        agent = FridayAgent(appContext)
        voice = createVoiceManager()
        FridayRuntime.update("WAKE HANDOFF", "Wake word accepted; preparing the voice session", true)
        FridayWakeCoordinator.pauseForSpeech {
            if (sessionActive.get() && !cleanedUp.get()) startListeningIfCurrent()
        }
    }

    private fun createVoiceManager(): VoiceManager = VoiceManager(appContext, object : VoiceManager.Listener {
        override fun onListening() {
            if (sessionActive.get() && !cleanedUp.get()) {
                FridayRuntime.update("LISTENING", "Microphone is listening for Imroz Sir's command", true)
            }
        }

        override fun onResult(text: String) { handle(text) }

        override fun onError(message: String) {
            if (!sessionActive.get() || cleanedUp.get()) return
            respond(message, finish = true)
        }
    })

    private fun startListeningIfCurrent() {
        if (sessionActive.get() && !cleanedUp.get()) voice.start()
    }

    private fun handle(text: String) {
        if (!sessionActive.get() || cleanedUp.get()) return
        val currentGeneration = ++interactionGeneration
        voice.cancel()

        if (shouldEndConversation(text)) {
            clearPendingConfirmation()
            respond("Okay Boss. Hands-free session ended.", finish = true)
            return
        }

        pendingConfirmation?.let { pending ->
            when (normalizeConfirmation(text)) {
                true -> {
                    clearPendingConfirmation()
                    when (val outcome = policy.validate(pending)) {
                        is ActionPolicyValidator.Outcome.Approved -> execute(outcome.action)
                        is ActionPolicyValidator.Outcome.RequiresConfirmation -> execute(outcome.action)
                        is ActionPolicyValidator.Outcome.Rejected -> respond(outcome.reason, finish = false)
                    }
                }
                false -> {
                    clearPendingConfirmation()
                    respond("Okay Boss, cancelled.", finish = false)
                }
                null -> {
                    armConfirmationExpiry()
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
                            armConfirmationExpiry()
                            respond("${local.text} Kya main ise kar doon, Boss?", finish = false)
                        } else execute(outcome.action, successText = local.text)
                    }
                    is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                        pendingConfirmation = outcome.action
                        armConfirmationExpiry()
                        respond("${local.text} ${outcome.prompt} Say yes or no, Boss.", finish = false)
                    }
                    is ActionPolicyValidator.Outcome.Rejected -> respond(outcome.reason, finish = false)
                }
            } else respond(local.text, finish = false)
            return
        }

        FridayRuntime.update("UNDERSTANDING", "Gemini is interpreting the open-ended request", true)
        agent.handle(text) { answer, _ ->
            mainHandler.post {
                if (!sessionActive.get() || cleanedUp.get() || interactionGeneration != currentGeneration) return@post
                respond(answer, finish = false)
            }
        }
    }

    private fun execute(action: FridayAction, successText: String = "Done, Boss.") {
        FridayRuntime.update("EXECUTING", "Running the requested Android action", true)
        val launched = runCatching { launcher.launch(action) }.getOrDefault(false)
        val observation = if (launched) ActionResultValidator.ExecutionObservation.HANDED_OFF
        else ActionResultValidator.ExecutionObservation.FAILED
        val result = resultValidator.fromExecution(
            action = action,
            observation = observation,
            detail = if (launched) "Android accepted the action hand-off; external completion is not observed" else "Android action hand-off failed"
        )
        if (result.status == ActionResultValidator.Status.HANDED_OFF) {
            FridayRuntime.update("HANDED OFF", result.detail, true)
            respond("Android accepted that request, Boss.", finish = false)
        } else if (result.status == ActionResultValidator.Status.SUCCESS && result.verified) {
            FridayRuntime.update("VERIFIED", result.detail, true)
            respond(successText, finish = false)
        } else {
            FridayRuntime.update("ACTION FAILED", result.detail, false)
            respond("I couldn't complete that action on this phone, Boss.", finish = false)
        }
    }

    private fun armConfirmationExpiry() {
        val token = ++confirmationGeneration
        mainHandler.postDelayed({
            if (sessionActive.get() && token == confirmationGeneration && pendingConfirmation != null) {
                clearPendingConfirmation()
                respond("Confirmation timed out, Boss. I didn't perform the action.", finish = false)
            }
        }, 45_000L)
    }

    private fun clearPendingConfirmation() {
        pendingConfirmation = null
        confirmationGeneration++
    }

    private fun normalizeConfirmation(text: String): Boolean? {
        val value = text.trim().lowercase(Locale.ROOT)
        if (value in setOf("yes", "haan", "ha", "han", "okay", "ok", "kar do", "do it", "sure", "ji")) return true
        if (value in setOf("no", "nahi", "nahin", "cancel", "mat karo", "don't", "nope", "nahi karo")) return false
        return null
    }

    private fun shouldEndConversation(text: String): Boolean {
        val value = text.trim().lowercase(Locale.ROOT)
        return value in setOf(
            "stop", "stop friday", "goodbye", "bye", "good night", "bas", "bas friday",
            "band ho jao", "band karo", "hands free off", "handsfree off", "standby"
        )
    }

    private fun respond(text: String, finish: Boolean) {
        if (!sessionActive.get()) return
        FridayRuntime.update("SPEAKING", text.take(240), true)
        tts.speak(text) {
            mainHandler.post {
                if (!sessionActive.get()) return@post
                if (finish) {
                    sessionActive.set(false)
                    finish()
                } else startListeningIfCurrent()
            }
        }
    }

    override fun onHide() {
        sessionActive.set(false)
        clearPendingConfirmation()
        cleanupAndResumeWake()
        super.onHide()
    }

    override fun onDestroy() {
        sessionActive.set(false)
        clearPendingConfirmation()
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
