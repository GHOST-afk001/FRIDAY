package com.friday.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Female-leaning assistant TTS using voices already installed on the user's phone. */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                val callback = synchronized(completionCallbacks) { completionCallbacks.remove(utteranceId) }
                callback?.invoke()
            }
            override fun onError(utteranceId: String) {
                val callback = synchronized(completionCallbacks) { completionCallbacks.remove(utteranceId) }
                callback?.invoke()
            }
        })
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) { onUnavailable(); return }
        tts?.setSpeechRate(0.92f)
        tts?.setPitch(1.02f)
        val preferred = tts?.voices?.firstOrNull { voice ->
            !voice.isNetworkConnectionRequired &&
                (voice.locale.language == "hi" || voice.locale.language == "en") &&
                voice.name.lowercase(Locale.ROOT).contains("female")
        }
        if (preferred != null) tts?.voice = preferred
        else tts?.language = Locale("en", "IN")
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (!ready || text.isBlank()) {
            onDone()
            return
        }
        val hindi = text.any { it in '\u0900'..'\u097F' }
        val target = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
        val available = tts?.isLanguageAvailable(target) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (available >= TextToSpeech.LANG_AVAILABLE) tts?.language = target
        val utteranceId = "friday-response-${System.nanoTime()}"
        synchronized(completionCallbacks) { completionCallbacks[utteranceId] = onDone }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            synchronized(completionCallbacks) { completionCallbacks.remove(utteranceId) }
            onDone()
        }
    }

    fun shutdown() {
        synchronized(completionCallbacks) { completionCallbacks.clear() }
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
