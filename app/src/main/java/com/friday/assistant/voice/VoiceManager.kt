package com.friday.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Lifecycle-aware wrapper for Android's system speech service. */
class VoiceManager(context: Context, private val listener: Listener) {
    interface Listener { fun onListening(); fun onResult(text: String); fun onError(message: String) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = createRecognizer(context)
    private var destroyed = false

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
            override fun onResults(results: Bundle?) {
                listener.onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) = listener.onError(errorMessage(error))
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
    }

    fun start() {
        if (destroyed) return
        runOnMain {
            if (destroyed) return@runOnMain
            if (recognizer == null) {
                listener.onError("Speech recognition is not available on this device.")
                return@runOnMain
            }
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Friday")
            })
        }
    }

    /** Cancel the current capture but keep the recognizer reusable for another tap. */
    fun cancel() {
        if (destroyed) return
        runOnMain { try { recognizer?.cancel() } catch (_: Exception) {} }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        runOnMain {
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
        }
    }

    private fun createRecognizer(context: Context): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        }
        return if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

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
