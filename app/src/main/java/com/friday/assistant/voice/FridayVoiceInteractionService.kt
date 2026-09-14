package com.friday.assistant.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat

/** System-level assistant service. It owns the lightweight wake-word lifecycle. */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        destroyed = false
        super.onCreate()
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startHandsFreeKeeper()
            FridayWakeCoordinator.start(this)
        }
    }

    private fun startHandsFreeKeeper() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FridayHandsFreeService::class.java)
            )
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
        showSession(args, 0)
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { if (!destroyed) block() }
    }

    override fun onShowSessionFailed(args: Bundle) {
        if (!destroyed) FridayWakeCoordinator.resumeAfterSpeech()
        super.onShowSessionFailed(args)
    }

    override fun onShutdown() {
        destroyed = true
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
