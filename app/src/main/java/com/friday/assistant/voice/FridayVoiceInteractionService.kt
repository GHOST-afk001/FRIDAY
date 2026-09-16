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

/** System assistant bridge. The persistent microphone/wake loop lives in one foreground service. */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        destroyed = false
        super.onCreate()
        FridayRuntime.update("ASSISTANT SERVICE", "FRIDAY Android Assistant bridge started", true)
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Microphone permission is required for Hey Friday", false)
            return
        }
        startHandsFreeKeeper()
        FridayRuntime.update("WAKE LISTENING", "FRIDAY background voice engine is active", true)
    }

    private fun startHandsFreeKeeper() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
        }.onSuccess {
            FridayRuntime.update("HANDS-FREE", "Persistent voice engine requested", true)
        }.onFailure {
            FridayRuntime.update("HANDS-FREE ERROR", "Android could not start the hands-free engine", false)
        }
    }

    internal fun showFridaySessionFromWake(confidence: Float) {
        if (destroyed) return
        val args = Bundle().apply { putFloat("friday_wake_confidence", confidence) }
        FridayRuntime.update("WAKE ACCEPTED", "Opening FRIDAY voice session", true)
        showSession(args, 0)
    }

    internal fun runOnMainThread(block: () -> Unit) {
        if (destroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { if (!destroyed) block() }
    }

    override fun onShowSessionFailed(args: Bundle) {
        if (!destroyed) FridayRuntime.update("SESSION FAILED", "Android rejected the FRIDAY voice session", false)
        super.onShowSessionFailed(args)
    }

    override fun onShutdown() {
        destroyed = true
        FridayRuntime.update("ASSISTANT STOPPED", "Android stopped FRIDAY Assistant bridge", false)
        stopHandsFreeKeeper()
        mainHandler.removeCallbacksAndMessages(null)
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        stopHandsFreeKeeper()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun stopHandsFreeKeeper() {
        runCatching { stopService(Intent(this, FridayHandsFreeService::class.java)) }
    }
}
