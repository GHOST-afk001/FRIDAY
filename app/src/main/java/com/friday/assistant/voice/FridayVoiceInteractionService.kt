package com.friday.assistant.voice

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import com.friday.assistant.runtime.FridayRuntime

/**
 * Lightweight system assistant bridge.
 *
 * Android keeps the selected VoiceInteractionService alive. Do not start the native
 * ONNX wake runtime from onReady(): a native wake failure here can make Android report
 * FRIDAY itself as crashing before the launcher is ever opened. Wake capture is started
 * only from an explicit FRIDAY-controlled path after the UI/runtime is known to be healthy.
 */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        FridayRuntime.update(
            "ASSISTANT SERVICE",
            "FRIDAY Android Assistant bridge ready; wake runtime deferred",
            true
        )
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        // The assistant service is now explicitly enabled. Start the guarded local wake
        // coordinator here; its detector catches native/model failures without crashing FRIDAY.
        FridayRuntime.update(
            "ASSISTANT READY",
            "FRIDAY Assistant ready; starting hands-free wake listener",
            true
        )
        runCatching { FridayWakeCoordinator.start(this) }
            .onFailure {
                FridayRuntime.update(
                    "WAKE START FAILED",
                    it.message ?: "FRIDAY wake listener could not start",
                    false
                )
            }
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
