package com.friday.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import com.friday.assistant.runtime.FridayRuntime

/**
 * Lightweight system assistant bridge.
 *
 * Android keeps the selected VoiceInteractionService alive so it can support background
 * hotwording. The actual microphone wake loop belongs to FridayWakeCoordinator; the heavy
 * SpeechRecognizer/TTS work belongs to FridayVoiceInteractionSession.
 */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        FridayRuntime.update("ASSISTANT SERVICE", "FRIDAY Android Assistant bridge ready", true)
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Open FRIDAY once and allow microphone access", false)
            return
        }

        runCatching {
            FridayWakeCoordinator.start(this)
        }.onSuccess {
            FridayRuntime.update("WAKE LISTENING", "FRIDAY is listening for Hey Friday", true)
        }.onFailure {
            FridayRuntime.update("WAKE ERROR", it.message ?: "FRIDAY wake engine could not start", false)
        }
    }

    internal fun showFridaySessionFromWake(confidence: Float) {
        if (destroyed) return
        val args = Bundle().apply { putFloat("friday_wake_confidence", confidence) }
        FridayRuntime.update("WAKE ACCEPTED", "Opening FRIDAY voice session", true)
        runCatching { showSession(args, 0) }
            .onFailure { FridayRuntime.update("SESSION FAILED", it.message ?: "Android rejected the FRIDAY voice session", false) }
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { if (!destroyed) block() }
    }

    override fun onShowSessionFailed(args: Bundle) {
        if (!destroyed) FridayRuntime.update("SESSION FAILED", "Android rejected the FRIDAY voice session", false)
        // The wake detector has already been released before this callback. Restore it.
        runCatching { FridayWakeCoordinator.resumeAfterSpeech() }
        super.onShowSessionFailed(args)
    }

    override fun onShutdown() {
        destroyed = true
        FridayWakeCoordinator.stop()
        FridayRuntime.update("ASSISTANT STOPPED", "Android stopped FRIDAY Assistant bridge", false)
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
