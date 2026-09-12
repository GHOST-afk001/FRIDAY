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

/** Lifecycle-aware one-shot wrapper for Android's system speech service. */
class VoiceManager(context: Context, private val listener: Listener) {
    interface Listener { fun onListening(); fun onResult(text: String); fun onError(message: String) }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var destroyed = false
    private var usingOnDevice = false
    private var fallbackAttempted = false
    private var generation = 0L

    init {
        runOnMain { createRecognizerLocked(preferOnDevice = true) }
    }

    fun start() {
        runOnMain {
            if (destroyed) return@runOnMain
            if (recognizer == null) {
                listener.onError("Speech recognition is not available on this device.")
                return@runOnMain
            }
            val startGeneration = ++generation
            fallbackAttempted = false
            recognizer?.setRecognitionListener(buildListener(recognizer!!, startGeneration))
            try {
                recognizer?.startListening(buildIntent())
            } catch (_: Exception) {
                if (startGeneration == generation && !destroyed) listener.onError("Speech recognition could not be started.")
            }
        }
    }

    /** Cancel the current capture but keep the recognizer reusable for another turn. */
    fun cancel() {
        runOnMain {
            if (destroyed) return@runOnMain
            generation++
            try { recognizer?.cancel() } catch (_: Exception) {}
        }
    }

    fun destroy() {
        runOnMain {
            if (destroyed) return@runOnMain
            destroyed = true
            generation++
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
        }
    }

    private fun createRecognizerLocked(preferOnDevice: Boolean) {
        if (destroyed || recognizer != null) return
        if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }.getOrNull()
            usingOnDevice = recognizer != null
        }
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrNull()
            usingOnDevice = false
        }
        recognizer?.setRecognitionListener(buildListener(recognizer!!, generation))
    }

    private fun buildListener(owner: SpeechRecognizer, expectedGeneration: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (owner === recognizer && !destroyed && expectedGeneration == generation) listener.onListening()
        }
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
            try { old?.cancel() } catch (_: Exception) {}
            try { old?.destroy() } catch (_: Exception) {}
            recognizer = null
            usingOnDevice = false
            createRecognizerLocked(preferOnDevice = false)
            if (recognizer == null) {
                listener.onError("The on-device speech service failed and no system speech service is available.")
                return@runOnMain
            }
            recognizer?.setRecognitionListener(buildListener(recognizer!!, expectedGeneration))
            try {
                recognizer?.startListening(buildIntent())
            } catch (_: Exception) {
                if (!destroyed && expectedGeneration == generation) listener.onError("The fallback speech service could not be started.")
            }
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
        return if (tag.startsWith("hi", ignoreCase = true) || tag.startsWith("en", ignoreCase = true)) tag else "en-IN"
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
