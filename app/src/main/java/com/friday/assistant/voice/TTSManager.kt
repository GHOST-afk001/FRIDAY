package com.friday.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Female-leaning assistant TTS using voices already installed on the user's phone. */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private val lock = Any()
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var destroyed = false
    private var pending: Pair<String, () -> Unit>? = null
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = complete(utteranceId)
            override fun onError(utteranceId: String) = complete(utteranceId)
        })
    }

    override fun onInit(status: Int) {
        val queued = synchronized(lock) {
            if (destroyed) return
            ready = status == TextToSpeech.SUCCESS
            if (!ready) null else pending.also { pending = null }
        }
        if (!ready) {
            onUnavailable()
            queued?.second?.invoke()
            return
        }
        tts?.setSpeechRate(0.92f)
        tts?.setPitch(1.02f)
        val preferred = tts?.voices?.firstOrNull { voice ->
            !voice.isNetworkConnectionRequired &&
                (voice.locale.language == "hi" || voice.locale.language == "en") &&
                voice.name.lowercase(Locale.ROOT).contains("female")
        }
        if (preferred != null) tts?.voice = preferred
        else tts?.language = Locale("en", "IN")
        queued?.let { speakNow(it.first, it.second) }
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        synchronized(lock) {
            if (destroyed) {
                onDone()
                return
            }
            if (!ready) {
                pending?.second?.invoke()
                pending = text to onDone
                return
            }
        }
        speakNow(text, onDone)
    }

    private fun speakNow(text: String, onDone: () -> Unit) {
        val engine = synchronized(lock) { if (destroyed || !ready) return else tts }
        if (engine == null) {
            onDone()
            return
        }
        val hindi = text.any { it in '\u0900'..'\u097F' }
        val target = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
        val available = engine.isLanguageAvailable(target)
        if (available >= TextToSpeech.LANG_AVAILABLE) engine.language = target
        val utteranceId = "friday-response-${System.nanoTime()}"
        synchronized(lock) {
            if (destroyed) {
                onDone()
                return
            }
            completionCallbacks[utteranceId] = onDone
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) complete(utteranceId)
    }

    private fun complete(utteranceId: String) {
        val callback = synchronized(lock) { completionCallbacks.remove(utteranceId) }
        callback?.invoke()
    }

    fun shutdown() {
        val callbacks = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            ready = false
            pending?.second?.let { listOf(it) }.orEmpty() + completionCallbacks.values.toList().also {
                pending = null
                completionCallbacks.clear()
            }
        }
        callbacks.forEach { it.invoke() }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
