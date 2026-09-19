package com.friday.assistant.voice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import com.friday.assistant.runtime.FridayRuntime

/**
 * Lightweight system assistant bridge.
 *
 * Android keeps the selected VoiceInteractionService alive. The wake detector
 * is isolated in :voice and is started only after Android reports the assistant
 * service ready. Native wake failures are caught by FridayWakeDetector and must
 * never be allowed to crash the launcher process.
 */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        FridayRuntime.update(
            "ASSISTANT SERVICE",
            "FRIDAY Android Assistant bridge is running",
            true
        )
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return

        FridayRuntime.update(
            "ASSISTANT READY",
            "Starting isolated Hey Friday wake listener",
            true
        )

        // Keep startup off the system callback itself. This gives Android a moment
        // to finish binding the voice process before microphone/native initialization.
        mainHandler.postDelayed({
            if (!destroyed) {
                runCatching {
                    FridayWakeCoordinator.start(this)
                }.onFailure {
                    FridayRuntime.update(
                        "WAKE START FAILED",
                        it.message ?: "Wake listener could not start",
                        false
                    )
                }
            }
        }, 1200L)
    }

    internal fun showFridaySessionFromWake(confidence: Float) {
        if (destroyed) return
        val args = Bundle().apply { putFloat("friday_wake_confidence", confidence) }
        FridayRuntime.update("WAKE ACCEPTED", "Opening FRIDAY voice session", true)
        runCatching { showSession(args, 0) }
            .onFailure {
                FridayRuntime.update(
                    "SESSION FAILED",
                    it.message ?: "Android rejected the FRIDAY voice session",
                    false
                )
            }
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { if (!destroyed) block() }
    }

    override fun onShowSessionFailed(args: Bundle) {
        if (!destroyed) {
            FridayRuntime.update(
                "SESSION FAILED",
                "Android rejected the FRIDAY voice session",
                false
            )
        }
        runCatching { FridayWakeCoordinator.resumeAfterSpeech() }
        super.onShowSessionFailed(args)
    }

    override fun onShutdown() {
        destroyed = true
        FridayWakeCoordinator.stop()
        FridayRuntime.update(
            "ASSISTANT STOPPED",
            "Android stopped FRIDAY Assistant bridge",
            false
        )
        mainHandler.removeCallbacksAndMessages(null)
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        FridayWakeCoordinator.stop()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
