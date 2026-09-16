package com.friday.assistant.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import com.friday.assistant.runtime.FridayRuntime

/** System-level assistant service. It owns the lightweight wake-word lifecycle. */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        destroyed = false
        super.onCreate()
        FridayRuntime.update("ASSISTANT SERVICE", "FRIDAY VoiceInteractionService started", true)
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Microphone permission is required for Hey Friday", false)
            return
        }

        // Android 15+ can reject audio-focus requests until the foreground service is actually
        // running. Start the keeper first, then give Android a short moment to promote it before
        // the wake detector initializes its AudioRecord path.
        startHandsFreeKeeper()
        FridayRuntime.update("STARTING WAKE", "Preparing hands-free microphone", true)
        mainHandler.postDelayed({
            if (!destroyed && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                FridayWakeCoordinator.start(this)
                FridayRuntime.update("WAKE LISTENING", "Say Hey Friday", true)
            }
        }, 1200L)
    }

    private fun startHandsFreeKeeper() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FridayHandsFreeService::class.java)
            ).also {
                FridayRuntime.update("HANDS-FREE", "Foreground voice keeper requested", true)
            }
        }.onFailure {
            FridayRuntime.update("HANDS-FREE ERROR", "Android could not start the hands-free keeper", false)
        }
    }

    private fun stopHandsFreeKeeper() {
        runCatching { stopService(Intent(this, FridayHandsFreeService::class.java)) }
    }

    internal fun showFridaySessionFromWake(confidence: Float) {
        if (destroyed) return
        // The coordinator already validated the wake generation and microphone handoff.
        // Do not gate this on isRunning(): a valid detector shutdown is expected here.
        val args = Bundle().apply { putFloat("friday_wake_confidence", confidence) }
        FridayRuntime.update("WAKE ACCEPTED", "Opening FRIDAY voice session", true)
        showSession(args, 0)
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { if (!destroyed) block() }
    }

    override fun onShowSessionFailed(args: Bundle) {
        if (!destroyed) {
            FridayRuntime.update("SESSION FAILED", "Android rejected the FRIDAY voice session; retrying wake", false)
            FridayWakeCoordinator.resumeAfterSpeech()
        }
        super.onShowSessionFailed(args)
    }

    override fun onShutdown() {
        destroyed = true
        FridayRuntime.update("ASSISTANT STOPPED", "Android stopped FRIDAY VoiceInteractionService", false)
        FridayWakeCoordinator.stop()
        stopHandsFreeKeeper()
        mainHandler.removeCallbacksAndMessages(null)
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        FridayWakeCoordinator.stop()
        stopHandsFreeKeeper()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
