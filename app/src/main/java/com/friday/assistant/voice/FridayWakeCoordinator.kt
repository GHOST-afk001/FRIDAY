package com.friday.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.security.SpeakerProfileStore
import com.friday.assistant.security.SpeakerVerificationBaseline
import java.lang.ref.WeakReference

/** Owns the single local microphone capture used by the optional wake-word detector. */
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
    private var verifier: SpeakerVerificationBaseline? = null
    private var profileStore: SpeakerProfileStore? = null
    private var enrollmentArmed = false

    fun start(service: FridayVoiceInteractionService) {
        synchronized(lock) {
            val appContext = service.applicationContext
            contextRef = WeakReference(appContext)
            serviceRef = WeakReference(service)
            if (verifier == null) {
                verifier = SpeakerVerificationBaseline()
                profileStore = SpeakerProfileStore(appContext)
                profileStore?.load()?.let { verifier?.restoreProfile(it) }
            }
            wakeEnabled = true
            generation++
            state = State.WAKE_LISTENING
            ensureStartedLocked()
        }
    }

    fun armSpeakerEnrollment(): Boolean = synchronized(lock) {
        if (state == State.STOPPED || contextRef?.get() == null) return false
        enrollmentArmed = true
        true
    }

    fun isSpeakerEnrolled(): Boolean = synchronized(lock) { verifier?.isEnrolled() == true }

    fun clearSpeakerEnrollment() {
        synchronized(lock) {
            enrollmentArmed = false
            verifier?.clearEnrollment()
            profileStore?.clear()
        }
    }

    /** Release the wake detector and invoke [onReleased] only after its worker has stopped. */
    fun pauseForSpeech(onReleased: () -> Unit = {}) {
        val oldDetector: FridayWakeDetector?
        synchronized(lock) {
            wakeEnabled = false
            generation++
            state = State.SPEECH_ACTIVE
            mainHandler.removeCallbacksAndMessages(null)
            oldDetector = detector
            oldDetector?.stop()
        }
        if (oldDetector == null) {
            mainHandler.post(onReleased)
            return
        }
        Thread({
            val released = oldDetector.stopAndWait(3000L)
            mainHandler.post {
                synchronized(lock) {
                    if (state != State.SPEECH_ACTIVE || wakeEnabled) return@post
                }
                if (released) onReleased() else FridayRuntime.update("MIC RELEASE FAILED", "Wake microphone did not release before speech", false)
            }
        }, "friday-wake-pause").start()
    }

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

    /** Never leave the coordinator stranded after a rejected wake/session handoff. */
    private fun recoverAfterWakeRejection(reason: String) {
        synchronized(lock) {
            if (state != State.RELEASING_WAKE || serviceRef?.get() == null) return
            wakeEnabled = true
            generation++
            state = State.WAKE_LISTENING
        }
        FridayRuntime.update("WAKE RECOVERY", reason, true)
        mainHandler.postDelayed({
            synchronized(lock) {
                if (wakeEnabled && state == State.WAKE_LISTENING && serviceRef?.get() != null) ensureStartedLocked()
            }
        }, 250L)
    }

    private fun onWakeAudioFocusLost(permanent: Boolean) {
        val shouldRecover = synchronized(lock) {
            if (!wakeEnabled || state != State.WAKE_LISTENING) return@synchronized false
            wakeEnabled = false
            generation++
            state = State.RELEASING_WAKE
            true
        }
        if (!shouldRecover) return
        FridayRuntime.update(
            "MIC RELEASED",
            if (permanent) "Audio focus was permanently lost; wake microphone released" else "Audio focus was temporarily lost; wake microphone released",
            true
        )
        mainHandler.postDelayed({
            synchronized(lock) {
                if (state != State.RELEASING_WAKE || serviceRef?.get() == null) return@synchronized
                wakeEnabled = true
                generation++
                state = State.WAKE_LISTENING
                ensureStartedLocked()
            }
        }, if (permanent) 1200L else 700L)
    }

    fun stop() {
        synchronized(lock) {
            wakeEnabled = false
            generation++
            state = State.STOPPED
            enrollmentArmed = false
            mainHandler.removeCallbacksAndMessages(null)
            detector?.stop()
            detector = null
            contextRef = null
            serviceRef = null
            verifier = null
            profileStore = null
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
        val newDetector = FridayWakeDetector(
            context = context,
            onWake = { confidence, audioFrame ->
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
                Thread({
                    val released = detectorToStop?.stopAndWait(3000L) ?: true
                    mainHandler.post {
                        if (!released) {
                            recoverAfterWakeRejection("Wake microphone did not release cleanly")
                            return@post
                        }
                        var enrollmentFailed = false
                        var speakerRejected = false
                        val service = synchronized(lock) {
                            if (wakeEnabled || state != State.RELEASING_WAKE) return@synchronized null
                            val currentVerifier = verifier
                            val currentStore = profileStore
                            if (enrollmentArmed) {
                                val enrolled = currentVerifier?.enroll(audioFrame) == true
                                if (enrolled) {
                                    currentStore?.save(currentVerifier?.exportProfile() ?: FloatArray(0))
                                    enrollmentArmed = false
                                    FridayRuntime.update("SPEAKER ENROLLED", "Local speaker profile stored on device", true)
                                } else {
                                    enrollmentFailed = true
                                    FridayRuntime.update("SPEAKER ENROLLMENT FAILED", "Wake audio was not sufficient for a profile", false)
                                }
                            }
                            if (!enrollmentFailed && currentVerifier?.isEnrolled() == true) {
                                val result = currentVerifier.verify(audioFrame)
                                if (!result.matched) {
                                    speakerRejected = true
                                    FridayRuntime.update("SPEAKER REJECTED", "Wake phrase did not match the enrolled speaker", false)
                                } else {
                                    FridayRuntime.update("SPEAKER VERIFIED", "Local speaker match ${"%.2f".format(result.similarity)}", true)
                                }
                            } else if (!enrollmentFailed) {
                                FridayRuntime.update("WAKE VERIFIED", "No speaker profile enrolled; continuing without biometric gating", true)
                            }
                            if (enrollmentFailed || speakerRejected) null else serviceRef?.get()
                        }
                        when {
                            enrollmentFailed -> recoverAfterWakeRejection("Speaker enrollment failed; wake listener restored")
                            speakerRejected -> recoverAfterWakeRejection("Speaker verification rejected wake; listener restored")
                            service == null -> recoverAfterWakeRejection("Voice interaction service is no longer available")
                            else -> service.showFridaySessionFromWake(confidence)
                        }
                    }
                }, "friday-wake-handoff").start()
            },
            onAudioFocusLost = ::onWakeAudioFocusLost,
            onStopped = {
                mainHandler.post {
                    synchronized(lock) {
                        if (wakeEnabled && state == State.WAKE_LISTENING && serviceRef?.get() != null && detector?.isRunning() != true) {
                            generation++
                            detector = null
                            state = State.WAKE_LISTENING
                            mainHandler.postDelayed({
                                synchronized(lock) {
                                    if (wakeEnabled && state == State.WAKE_LISTENING && serviceRef?.get() != null) ensureStartedLocked()
                                }
                            }, 500L)
                        }
                    }
                }
            }
        )
        detector = newDetector
        newDetector.start()
    }
}
