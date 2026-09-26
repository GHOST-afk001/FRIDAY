package com.friday.assistant.voice

import android.content.Intent
import android.speech.RecognitionService

/**
 * Compatibility RecognitionService required by Android's VoiceInteractionService metadata.
 * FRIDAY uses the system recognizer through VoiceManager for actual speech capture.
 */
class FridayRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        // This compatibility service is not used for FRIDAY command capture.
        // Leave the callback untouched rather than reporting a non-existent error code.
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit
}
