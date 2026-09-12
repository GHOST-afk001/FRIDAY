package com.friday.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** TTS tuned for natural Hindi + Indian English mixed speech. */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var destroyed = false
    private var pending: Pair<String, () -> Unit>? = null
    private val completionCallbacks = mutableMapOf<String, () -> Unit>()
    private val speechGeneration = AtomicLong(0L)

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

        tts?.setSpeechRate(0.88f)
        tts?.setPitch(1.0f)
        queued?.let { speakNow(it.first, it.second, speechGeneration.incrementAndGet()) }
    }

    /** Starts a new utterance generation and invalidates any older speech callback chain. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            mainHandler.post(onDone)
            return
        }
        val generation = speechGeneration.incrementAndGet()
        synchronized(lock) {
            if (destroyed) {
                mainHandler.post(onDone)
                return
            }
            // A new answer supersedes an older answer. This prevents stale onDone callbacks
            // from reopening STT or speaking the tail of an older response a second time.
            completionCallbacks.clear()
            if (!ready) {
                pending = text to onDone
                return
            }
        }
        tts?.stop()
        speakNow(text, onDone, generation)
    }

    private fun speakNow(text: String, onDone: () -> Unit, generation: Long) {
        val engine = synchronized(lock) { if (destroyed || !ready) return else tts }
        if (engine == null) {
            mainHandler.post { if (speechGeneration.get() == generation) onDone() }
            return
        }

        val segments = splitByScript(text)
        speakSegment(engine, segments, 0, onDone, generation)
    }

    private fun speakSegment(
        engine: TextToSpeech,
        segments: List<String>,
        index: Int,
        onDone: () -> Unit,
        generation: Long
    ) {
        if (speechGeneration.get() != generation) return
        if (index >= segments.size) {
            mainHandler.post { if (speechGeneration.get() == generation && !destroyed) onDone() }
            return
        }
        synchronized(lock) {
            if (destroyed || speechGeneration.get() != generation) return
        }

        val segment = segments[index]
        val target = if (containsDevanagari(segment)) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
        val available = engine.isLanguageAvailable(target)
        if (available >= TextToSpeech.LANG_AVAILABLE) engine.language = target

        val preferred = engine.voices?.firstOrNull { voice ->
            !voice.isNetworkConnectionRequired &&
                voice.locale.language == target.language &&
                voice.name.lowercase(Locale.ROOT).let { name ->
                    name.contains("female") || name.contains("fem") || name.contains("woman") ||
                        name.contains("samantha") || name.contains("zira")
                }
        }
        if (preferred != null) engine.voice = preferred

        val utteranceId = "friday-${generation}-${index}-${System.nanoTime()}"
        synchronized(lock) {
            if (destroyed || speechGeneration.get() != generation) return
            completionCallbacks[utteranceId] = {
                if (speechGeneration.get() == generation && !destroyed) {
                    speakSegment(engine, segments, index + 1, onDone, generation)
                }
            }
        }

        // QUEUE_FLUSH only for the first segment; subsequent segments are chained with ADD.
        // This avoids stale flush callbacks from causing duplicate final responses.
        val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(segment, queueMode, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) complete(utteranceId)
    }

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
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            ready = false
            pending = null
            completionCallbacks.clear()
            speechGeneration.incrementAndGet()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
