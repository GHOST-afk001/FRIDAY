package com.friday.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat

/** System-level assistant service. It owns only the lightweight wake-word lifecycle. */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onReady() {
        super.onReady()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            FridayWakeCoordinator.start(this)
        }
    }

    internal fun showFridaySessionFromWake(confidence: Float) {
        if (!FridayWakeCoordinator.isRunning()) {
            val args = Bundle().apply { putFloat("friday_wake_confidence", confidence) }
            showSession(args, 0)
        }
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onShowSessionFailed(args: Bundle) {
        // If the system could not create the session, return to wake listening.
        FridayWakeCoordinator.resumeAfterSpeech()
        super.onShowSessionFailed(args)
    }

    override fun onShutdown() {
        FridayWakeCoordinator.stop()
        super.onShutdown()
    }
}
