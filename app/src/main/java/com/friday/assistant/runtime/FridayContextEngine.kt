package com.friday.assistant.runtime

import java.util.Locale

class FridayContextEngine {
    data class Context(
        val original: String,
        val normalized: String,
        val language: Language,
        val urgency: Urgency,
        val wantsHandsFree: Boolean,
        val likelyFollowUp: Boolean
    )

    enum class Language { HINGLISH, ENGLISH, OTHER }
    enum class Urgency { NORMAL, HIGH }

    private var lastInput: String? = null

    @Synchronized
    fun analyze(input: String): Context {
        val normalized = input.trim().lowercase(Locale.ROOT)
        val hindiSignals = listOf("karo", "kar do", "batao", "kholo", "band", "chalu", "hai", "mujhe", "mere", "kal", "aaj")
        val englishSignals = listOf("please", "open", "close", "tell", "show", "search", "set", "send", "call")
        val hi = hindiSignals.count(normalized::contains)
        val en = englishSignals.count(normalized::contains)
        val language = when {
            hi > 0 && en > 0 -> Language.HINGLISH
            hi > en -> Language.HINGLISH
            en > 0 -> Language.ENGLISH
            else -> Language.OTHER
        }
        val urgency = if (listOf("urgent", "emergency", "sos", "help me", "jaldi").any(normalized::contains)) Urgency.HIGH else Urgency.NORMAL
        val followUp = lastInput != null && listOf("haan", "yes", "okay", "continue", "then", "aur", "iske baad").any(normalized::contains)
        val result = Context(input, normalized, language, urgency, normalized.contains("without tapping") || normalized.contains("hands free"), followUp)
        lastInput = input
        return result
    }

    @Synchronized fun reset() { lastInput = null }
}
