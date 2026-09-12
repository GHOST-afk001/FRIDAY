package com.friday.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** TTS tuned for natural Hindi + Indian English mixed speech. */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
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
            mainHandler.post { if (!destroyed) onUnavailable() }
            queued?.second?.let { callback -> mainHandler.post(callback) }
            return
        }

        // Slightly slower and lower-pitch than the stock assistant defaults.
        // The actual installed phone voice remains authoritative.
        tts?.setSpeechRate(0.88f)
        tts?.setPitch(1.0f)
        queued?.let { speakNow(it.first, it.second) }
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            mainHandler.post(onDone)
            return
        }
        synchronized(lock) {
            if (destroyed) {
                mainHandler.post(onDone)
                return
            }
            if (!ready) {
                pending?.second?.let { mainHandler.post(it) }
                pending = text to onDone
                return
            }
        }
        speakNow(text, onDone)
    }

    private fun speakNow(text: String, onDone: () -> Unit) {
        val engine = synchronized(lock) { if (destroyed || !ready) return else tts }
        if (engine == null) {
            mainHandler.post(onDone)
            return
        }

        val segments = splitByScript(text)
        speakSegment(engine, segments, 0, onDone)
    }

    private fun speakSegment(
        engine: TextToSpeech,
        segments: List<String>,
        index: Int,
        onDone: () -> Unit
    ) {
        if (index >= segments.size) {
            mainHandler.post(onDone)
            return
        }
        synchronized(lock) { if (destroyed) { mainHandler.post(onDone); return } }

        val segment = segments[index]
        val target = if (containsDevanagari(segment)) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
        val available = engine.isLanguageAvailable(target)
        if (available >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = target
        }

        // Prefer an installed female voice for the requested language when the engine exposes
        // a gender hint in its voice name. Otherwise keep the engine's normal regional voice.
        val preferred = engine.voices?.firstOrNull { voice ->
            !voice.isNetworkConnectionRequired &&
                voice.locale.language == target.language &&
                voice.name.lowercase(Locale.ROOT).let { name ->
                    name.contains("female") || name.contains("fem") || name.contains("woman") ||
                        name.contains("samantha") || name.contains("zira")
                }
        }
        if (preferred != null) engine.voice = preferred

        val utteranceId = "friday-response-${System.nanoTime()}"
        synchronized(lock) {
            if (destroyed) {
                mainHandler.post(onDone)
                return
            }
            completionCallbacks[utteranceId] = {
                speakSegment(engine, segments, index + 1, onDone)
            }
        }
        val result = engine.speak(segment, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) complete(utteranceId)
    }

    /** Keep Hindi in hi-IN and English/Hinglish fragments in en-IN for much cleaner pronunciation. */
    private fun splitByScript(text: String): List<String> {
        val result = mutableListOf<String>()
        val builder = StringBuilder()
        var devanagari: Boolean? = null

        fun flush() {
            if (builder.isNotEmpty()) {
                result += builder.toString()
                builder.setLength(0)
            }
        }

        for (char in text) {
            val current = char in '\u0900'..'\u097F'
            if (devanagari == null) devanagari = current
            if (current != devanagari && builder.isNotEmpty()) {
                flush()
                devanagari = current
            }
            builder.append(char)
        }
        flush()

        return result.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
    }

    private fun containsDevanagari(text: String): Boolean = text.any { it in '\u0900'..'\u097F' }

    private fun complete(utteranceId: String) {
        val callback = synchronized(lock) { completionCallbacks.remove(utteranceId) }
        callback?.let { mainHandler.post(it) }
    }

    fun shutdown() {
        val callbacks = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            ready = false
            val pendingCallback = pending?.second
            val activeCallbacks = completionCallbacks.values.toList()
            pending = null
            completionCallbacks.clear()
            listOfNotNull(pendingCallback) + activeCallbacks
        }
        callbacks.forEach { mainHandler.post(it) }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
