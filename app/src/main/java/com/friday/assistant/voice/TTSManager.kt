package com.friday.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Female-leaning assistant TTS using voices already installed on the user's phone. */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false

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

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val hindi = text.any { it in '\u0900'..'\u097F' }
        val target = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
        if (tts?.isLanguageAvailable(target) ?: TextToSpeech.LANG_NOT_SUPPORTED >= TextToSpeech.LANG_AVAILABLE) {
            tts?.language = target
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "friday-response")
    }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
