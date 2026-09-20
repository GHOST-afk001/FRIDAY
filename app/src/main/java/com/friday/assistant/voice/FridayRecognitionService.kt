package com.friday.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Compatibility RecognitionService required by Android's VoiceInteractionService metadata.
 *
 * Modern Android versions do not use this service for FRIDAY's actual speech capture;
 * VoiceManager talks to the user's selected system recognizer. This service exists so
 * Android can qualify FRIDAY as an Assistant role holder and remain safe on older releases.
 */
class FridayRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_SERVICE_NOT_ALLOWED)
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit
}
