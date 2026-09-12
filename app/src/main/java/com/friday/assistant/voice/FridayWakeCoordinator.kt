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
    private enum class State { STOPPED, WAKE_LISTENING, RELEASING_WAKE, SPEECH_ACTIVE, RELEASING_SPEECH }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var contextRef: WeakReference<Context>? = null
    private var serviceRef: WeakReference<FridayVoiceInteractionService>? = null
    private var detector: FridayWakeDetector? = null
    private var wakeEnabled = false
    private var generation = 0L
    private var state = State.STOPPED

    fun start(service: FridayVoiceInteractionService) {
        synchronized(lock) {
            contextRef = WeakReference(service.applicationContext)
            serviceRef = WeakReference(service)
            wakeEnabled = true
            generation++
            state = State.WAKE_LISTENING
            ensureStartedLocked()
        }
    }

    /** Release AudioRecord before SpeechRecognizer is allowed to listen. */
    fun pauseForSpeech() {
        synchronized(lock) {
            wakeEnabled = false
            generation++
            state = State.SPEECH_ACTIVE
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
            state = State.RELEASING_SPEECH
            oldDetector = detector
            oldDetector?.stop()
        }
        Thread({
            val released = oldDetector?.stopAndWait(3000L) ?: true
            mainHandler.post {
                synchronized(lock) {
                    if (!wakeEnabled || state != State.RELEASING_SPEECH) return@synchronized
                    if (serviceRef?.get() == null) return@synchronized
                    if (released) {
                        state = State.WAKE_LISTENING
                        ensureStartedLocked()
                    } else {
                        // A timeout is not success. Retry recovery later instead of opening
                        // a second microphone owner while the old recorder may still exist.
                        mainHandler.postDelayed({
                            synchronized(lock) {
                                if (wakeEnabled && state == State.RELEASING_SPEECH && serviceRef?.get() != null) {
                                    val retry = detector
                                    Thread({
                                        val retryReleased = retry?.stopAndWait(3000L) ?: true
                                        mainHandler.post {
                                            synchronized(lock) {
                                                if (!wakeEnabled || state != State.RELEASING_SPEECH) return@synchronized
                                                if (retryReleased) {
                                                    state = State.WAKE_LISTENING
                                                    ensureStartedLocked()
                                                }
                                            }
                                        }
                                    }, "friday-wake-recovery").start()
                                }
                            }
                        }, 1000L)
                    }
                }
            }
        }, "friday-wake-resume").start()
    }

    fun stop() {
        synchronized(lock) {
            wakeEnabled = false
            generation++
            state = State.STOPPED
            mainHandler.removeCallbacksAndMessages(null)
            detector?.stop()
            detector = null
            contextRef = null
            serviceRef = null
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { state == State.WAKE_LISTENING && detector?.isRunning() == true }

    private fun ensureStartedLocked() {
        if (!wakeEnabled || state != State.WAKE_LISTENING) return
        if (detector?.isRunning() == true) return
        detector?.stop()
        detector = null

        val context = contextRef?.get() ?: return
        if (serviceRef?.get() == null) return
        val callbackGeneration = generation
        val newDetector = FridayWakeDetector(context) { confidence ->
            var accepted = false
            val detectorToStop: FridayWakeDetector?
            synchronized(lock) {
                if (wakeEnabled && state == State.WAKE_LISTENING && generation == callbackGeneration) {
                    accepted = true
                    wakeEnabled = false
                    generation++
                    state = State.RELEASING_WAKE
                    detectorToStop = detector
                    detector?.stop()
                } else detectorToStop = null
            }
            if (!accepted) return@FridayWakeDetector

            // Never rely on a fixed sleep: wait for the detector worker's finally block,
            // which owns the definitive AudioRecord release, before opening speech input.
            Thread({
                val released = detectorToStop?.stopAndWait(3000L) ?: true
                mainHandler.post {
                    val service = synchronized(lock) {
                        if (wakeEnabled || state != State.RELEASING_WAKE || !released) return@synchronized null
                        serviceRef?.get()
                    }
                    if (service != null) {
                        service.showFridaySessionFromWake(confidence)
                    } else if (!released) {
                        // The old recorder was not confirmed released. Recovery will be
                        // attempted by the normal wake lifecycle rather than starting STT.
                        resumeAfterSpeech()
                    }
                }
            }, "friday-wake-handoff").start()
        }
        detector = newDetector
        newDetector.start()
    }
}
