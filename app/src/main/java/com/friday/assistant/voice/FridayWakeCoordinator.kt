package com.friday.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Owns the single local microphone capture used by the optional wake-word detector.
 * SpeechRecognizer and AudioRecord must never be active at the same time.
 */
object FridayWakeCoordinator {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var contextRef: WeakReference<Context>? = null
    private var serviceRef: WeakReference<FridayVoiceInteractionService>? = null
    private var detector: FridayWakeDetector? = null
    private var wakeEnabled = false

    fun start(service: FridayVoiceInteractionService) {
        synchronized(lock) {
            contextRef = WeakReference(service.applicationContext)
            serviceRef = WeakReference(service)
            wakeEnabled = true
            ensureStartedLocked()
        }
    }

    /** Release AudioRecord before SpeechRecognizer is allowed to listen. */
    fun pauseForSpeech() {
        synchronized(lock) {
            wakeEnabled = false
            detector?.stop()
            detector = null
        }
    }

    /** Re-acquire the wake microphone only after SpeechRecognizer has been destroyed. */
    fun resumeAfterSpeech() {
        synchronized(lock) {
            wakeEnabled = true
            ensureStartedLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            wakeEnabled = false
            detector?.stop()
            detector = null
            contextRef = null
            serviceRef = null
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { detector?.isRunning() == true }

    private fun ensureStartedLocked() {
        if (!wakeEnabled) return
        if (detector?.isRunning() == true) return
        detector?.stop()
        detector = null

        val context = contextRef?.get() ?: return
        if (serviceRef?.get() == null) return
        detector = FridayWakeDetector(context) { confidence ->
            pauseForSpeech()
            mainHandler.post {
                serviceRef?.get()?.showFridaySessionFromWake(confidence)
            }
        }.also { it.start() }
    }
}
