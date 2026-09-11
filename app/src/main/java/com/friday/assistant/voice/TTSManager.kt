package com.friday.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Uses the system TTS engine; it degrades gracefully when no voice is installed. */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) onUnavailable()
        else tts?.language = Locale("hi", "IN").takeIf { tts?.isLanguageAvailable(it) != TextToSpeech.LANG_MISSING_DATA && tts?.isLanguageAvailable(it) != TextToSpeech.LANG_NOT_SUPPORTED } ?: Locale.US
    }
    fun speak(text: String) { if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday-response") }
    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
