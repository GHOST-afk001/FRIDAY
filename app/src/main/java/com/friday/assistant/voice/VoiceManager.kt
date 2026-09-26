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

            // Samsung/Google speech services can reject a background recognition request
            // even though microphone permission is granted. Retry once with the Android
            // on-device recognizer before reporting an error.
            if (!retried && Build.VERSION.SDK_INT >= 31 &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
            ) {
                retried = true
                replaceWithOnDevice(currentGeneration)
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

    private fun replaceWithOnDevice(currentGeneration: Long) {
        runMain {
            if (destroyed || currentGeneration != generation) return@runMain

            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }

            recognizer = runCatching {
                if (Build.VERSION.SDK_INT >= 31 &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                } else null
            }.getOrNull()
            usingOnDevice = recognizer != null

            val current = recognizer
            if (current == null) {
                listener.onError("No usable Android speech recognition service is available.")
                return@runMain
            }

            runCatching {
                current.setRecognitionListener(listenerFor(current, currentGeneration))
                current.startListening(intent())
            }.onFailure {
                listener.onError("On-device speech recognition could not be started.")
            }
        }
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        // Hinglish first: Indian English is the primary recognition locale, with
        // Hindi enabled as the secondary language on Android 14+ language-switching APIs.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
        if (Build.VERSION.SDK_INT >= 34) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                arrayListOf("en-IN", "hi-IN")
            )
        }
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
