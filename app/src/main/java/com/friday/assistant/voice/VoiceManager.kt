package com.friday.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Lifecycle-aware wrapper for Android's free on-device/system speech service. */
class VoiceManager(context: Context, private val listener: Listener) {
    interface Listener { fun onListening(); fun onResult(text: String); fun onError(message: String) }
    private val recognizer = if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null

    init { recognizer?.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
        override fun onResults(results: Bundle?) { listener.onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()) }
        override fun onError(error: Int) = listener.onError(errorMessage(error))
        override fun onBeginningOfSpeech() {} ; override fun onBufferReceived(buffer: ByteArray?) {} ; override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {} ; override fun onPartialResults(partialResults: Bundle?) {}
        override fun onRmsChanged(rmsdB: Float) {}
    }) }

    fun start() {
        if (recognizer == null) { listener.onError("Speech recognition is not available on this device."); return }
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Friday")
        })
    }
    fun destroy() = recognizer?.destroy()
    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording failed. Please try again."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was interrupted. Please try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service is unavailable. Check your connection or installed speech service."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I could not hear that. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Please try again."
        else -> "Speech recognition failed. Please try again."
    }
}
