package com.friday.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * RecognitionService declaration required for FRIDAY to qualify as an Android
 * VoiceInteractionService/Assistant on platform role implementations.
 *
 * FRIDAY's actual voice session uses VoiceManager, which prefers Android's
 * on-device recognizer and falls back to the system recognizer. This service
 * exists as the assistant bridge required by the platform metadata.
 */
class FridayRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback
    ) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
