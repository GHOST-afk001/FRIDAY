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

/** Lifecycle-aware, crash-safe one-shot wrapper for Android SpeechRecognizer. */
class VoiceManager(context: Context, private val listener: Listener) {
    interface Listener { fun onListening(); fun onResult(text: String); fun onError(message: String) }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var destroyed = false
    private var usingOnDevice = false
    private var fallbackAttempted = false
    private var generation = 0L

    init { runOnMain { runCatching { createRecognizerLocked(true) }.onFailure { listener.onError("Speech recognition could not be initialized.") } } }

    fun start() {
        runOnMain {
            if (destroyed) return@runOnMain
            val current = recognizer
            if (current == null) {
                listener.onError("Speech recognition is not available on this device.")
                return@runOnMain
            }
            val startGeneration = ++generation
            fallbackAttempted = false
            runCatching {
                current.setRecognitionListener(buildListener(current, startGeneration))
                current.startListening(buildIntent())
            }.onFailure {
                if (startGeneration == generation && !destroyed) listener.onError("Speech recognition could not be started.")
            }
        }
    }

    fun cancel() {
        runOnMain {
            if (destroyed) return@runOnMain
            generation++
            runCatching { recognizer?.cancel() }
        }
    }

    fun destroy() {
        runOnMain {
            if (destroyed) return@runOnMain
            destroyed = true
            generation++
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun createRecognizerLocked(preferOnDevice: Boolean) {
        if (destroyed || recognizer != null) return
        if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)) {
            recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }.getOrNull()
            usingOnDevice = recognizer != null
        }
        if (recognizer == null && runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)) {
            recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
            usingOnDevice = false
        }
        recognizer?.let { current -> current.setRecognitionListener(buildListener(current, generation)) }
    }

    private fun buildListener(owner: SpeechRecognizer, expectedGeneration: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (owner === recognizer && !destroyed && expectedGeneration == generation) listener.onListening() }
        override fun onResults(results: Bundle?) {
            if (owner !== recognizer || destroyed || expectedGeneration != generation) return
            listener.onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }
        override fun onError(error: Int) {
            if (owner !== recognizer || destroyed || expectedGeneration != generation) return
            if (usingOnDevice && !fallbackAttempted && error in setOf(
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                )) {
                fallbackAttempted = true
                replaceWithSystemRecognizer(expectedGeneration)
                return
            }
            listener.onError(errorMessage(error))
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onRmsChanged(rmsdB: Float) {}
    }

    private fun replaceWithSystemRecognizer(expectedGeneration: Long) {
        runOnMain {
            if (destroyed || expectedGeneration != generation) return@runOnMain
            val old = recognizer
            runCatching { old?.cancel() }
            runCatching { old?.destroy() }
            recognizer = null
            usingOnDevice = false
            runCatching { createRecognizerLocked(false) }.onFailure { listener.onError("The speech service could not be restarted.") }
            val current = recognizer
            if (current == null) {
                listener.onError("The on-device speech service failed and no system speech service is available.")
                return@runOnMain
            }
            runCatching {
                current.setRecognitionListener(buildListener(current, expectedGeneration))
                current.startListening(buildIntent())
            }.onFailure { if (!destroyed && expectedGeneration == generation) listener.onError("The fallback speech service could not be started.") }
        }
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocale())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Friday")
    }

    private fun speechLocale(): String {
        val tag = Locale.getDefault().toLanguageTag()
        return if (tag.startsWith("hi", true) || tag.startsWith("en", true)) tag else "en-IN"
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
