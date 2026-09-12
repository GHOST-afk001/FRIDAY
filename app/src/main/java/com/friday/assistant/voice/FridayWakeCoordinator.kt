package com.friday.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Owns the single local microphone capture used by the optional wake-word detector.
 * SpeechRecognizer and AudioRecord must never be intentionally active at the same time.
 */
object FridayWakeCoordinator {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var contextRef: WeakReference<Context>? = null
    private var serviceRef: WeakReference<FridayVoiceInteractionService>? = null
    private var detector: FridayWakeDetector? = null
    private var wakeEnabled = false
    private var generation = 0L

    fun start(service: FridayVoiceInteractionService) {
        synchronized(lock) {
            contextRef = WeakReference(service.applicationContext)
            serviceRef = WeakReference(service)
            wakeEnabled = true
            generation++
            ensureStartedLocked()
        }
    }

    /** Release AudioRecord before SpeechRecognizer is allowed to listen. */
    fun pauseForSpeech() {
        synchronized(lock) {
            wakeEnabled = false
            generation++
            mainHandler.removeCallbacksAndMessages(null)
            detector?.stop()
        }
    }

    /** Re-acquire the wake microphone only after the previous detector has actually stopped. */
    fun resumeAfterSpeech() {
        val oldDetector: FridayWakeDetector?
        synchronized(lock) {
            wakeEnabled = true
            generation++
            oldDetector = detector
            oldDetector?.stop()
        }
        Thread({
            oldDetector?.stopAndWait(1500L)
            mainHandler.post {
                synchronized(lock) {
                    if (wakeEnabled) ensureStartedLocked()
                }
            }
        }, "friday-wake-resume").start()
    }

    fun stop() {
        synchronized(lock) {
            wakeEnabled = false
            generation++
            mainHandler.removeCallbacksAndMessages(null)
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
        val callbackGeneration = generation
        detector = FridayWakeDetector(context) { confidence ->
            var accepted = false
            val detectorToStop: FridayWakeDetector?
            synchronized(lock) {
                if (wakeEnabled && generation == callbackGeneration) {
                    accepted = true
                    wakeEnabled = false
                    generation++
                    detectorToStop = detector
                    detector?.stop()
                } else detectorToStop = null
            }
            if (!accepted) return@FridayWakeDetector

            // Never rely on a fixed sleep: wait for the detector worker's finally block,
            // which owns the definitive AudioRecord release, before opening speech input.
            Thread({
                detectorToStop?.stopAndWait(1500L)
                mainHandler.post {
                    synchronized(lock) {
                        if (wakeEnabled) return@post
                        if (serviceRef?.get() == null) return@post
                    }
                    serviceRef?.get()?.showFridaySessionFromWake(confidence)
                }
            }, "friday-wake-handoff").start()
        }.also { it.start() }
    }
}
