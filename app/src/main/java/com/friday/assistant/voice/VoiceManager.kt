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
import com.friday.assistant.runtime.FridayStateFlow
import java.util.Locale

/** Reliable one-shot Android speech wrapper with a safe system/on-device fallback. */
class VoiceManager(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onListening()
        fun onResult(text: String)
        fun onError(message: String)
        fun onAmplitude(value: Float) = Unit
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var destroyed = false
    private var generation = 0L
    private var retried = false
    private var usingOnDevice = false

    init {
        runMain { createRecognizer() }
    }

    fun start() {
        runMain {
            if (destroyed) return@runMain
            val current = recognizer
            if (current == null) {
                listener.onError("No Android speech recognition service is available.")
                return@runMain
            }

            val currentGeneration = ++generation
            retried = false
            runCatching {
                current.setRecognitionListener(listenerFor(current, currentGeneration))
                current.startListening(intent())
            }.onFailure {
                listener.onError("Could not start microphone listening.")
            }
        }
    }

    fun cancel() {
        runMain {
            generation++
            runCatching { recognizer?.cancel() }
            FridayStateFlow.resetAmplitude()
        }
    }

    fun destroy() {
        runMain {
            if (destroyed) return@runMain
            destroyed = true
            generation++
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            FridayStateFlow.resetAmplitude()
        }
    }

    private fun createRecognizer() {
        if (destroyed || recognizer != null) return

        recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.getOrNull()
        usingOnDevice = false

        if (
            recognizer == null &&
            Build.VERSION.SDK_INT >= 31 &&
            runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            }.getOrDefault(false)
        ) {
            recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }.getOrNull()
            usingOnDevice = recognizer != null
        }

        val current = recognizer
        if (current == null) {
            listener.onError("Android SpeechRecognizer is unavailable. Install or enable a speech recognition service.")
            return
        }

        current.setRecognitionListener(listenerFor(current, generation))
    }

    private fun listenerFor(
        owner: SpeechRecognizer,
        currentGeneration: Long
    ): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (owner === recognizer && !destroyed && currentGeneration == generation) {
                listener.onListening()
            }
        }

        override fun onResults(results: Bundle?) {
            if (owner !== recognizer || destroyed || currentGeneration != generation) return
            FridayStateFlow.resetAmplitude()
            listener.onResult(
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            )
        }

        override fun onError(error: Int) {
            if (owner !== recognizer || destroyed || currentGeneration != generation) return
            FridayStateFlow.resetAmplitude()

            if (!retried && usingOnDevice) {
                retried = true
                replaceWithSystem(currentGeneration)
                return
            }

            listener.onError(message(error))
        }

        override fun onRmsChanged(value: Float) {
            val normalized = ((value + 2f) / 12f).coerceIn(0f, 1f)
            FridayStateFlow.updateAmplitude(normalized)
            listener.onAmplitude(normalized)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            FridayStateFlow.updateAmplitude(0f)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit
    }

    private fun replaceWithSystem(currentGeneration: Long) {
        runMain {
            if (destroyed || currentGeneration != generation) return@runMain

            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }

            recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }.getOrNull()
            usingOnDevice = false

            val current = recognizer
            if (current == null) {
                listener.onError("No system speech service is available.")
                return@runMain
            }

            runCatching {
                current.setRecognitionListener(listenerFor(current, currentGeneration))
                current.startListening(intent())
            }.onFailure {
                listener.onError("System speech service could not be started.")
            }
        }
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocale())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to FRIDAY")
    }

    private fun speechLocale(): String {
        val language = Locale.getDefault().toLanguageTag()
        return if (
            language.startsWith("hi", ignoreCase = true) ||
            language.startsWith("en", ignoreCase = true)
        ) {
            language
        } else {
            "en-IN"
        }
    }

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
    }

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording failed."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was interrupted."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service/network unavailable."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Speak clearly and try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy."
        else -> "Speech recognition failed (error $error)."
    }
}
