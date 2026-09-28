package com.friday.assistant.runtime

import java.util.Locale

class FridayBehaviorEngine {
    enum class Mood { CALM, HAPPY, SAD, ANGRY, STRESSED, EXCITED, PLAYFUL }
    data class State(val mood: Mood, val style: String, val systemPrompt: String)

    fun analyze(input: String, rememberedMode: String): State {
        val text = input.trim().lowercase(Locale.ROOT)
        val mood = when {
            listOf("gussa", "angry", "irritated", "bakwas", "fuck", "hate").any(text::contains) -> Mood.ANGRY
            listOf("sad", "dukhi", "rona", "hurt", "mood kharab").any(text::contains) -> Mood.SAD
            listOf("stress", "tension", "pareshan", "problem", "worried").any(text::contains) -> Mood.STRESSED
            listOf("happy", "khush", "love", "pyaar", "awesome", "mast").any(text::contains) -> Mood.HAPPY
            listOf("excited", "party", "lets go", "chalo").any(text::contains) -> Mood.EXCITED
            listOf("haha", "lol", "mazak", "joke", "funny").any(text::contains) -> Mood.PLAYFUL
            else -> Mood.CALM
        }
        val style = when (mood) {
            Mood.SAD -> "soft, reassuring, patient"
            Mood.STRESSED -> "calm, grounding, concise"
            Mood.ANGRY -> "calm but firm, never escalating"
            Mood.HAPPY, Mood.EXCITED -> "warm, cheerful, energetic"
            Mood.PLAYFUL -> "light, witty, affectionate"
            Mood.CALM -> if (rememberedMode == "night") "soft, quiet, gentle" else "natural, warm, concise"
        }
        val prompt = """
            Persona: FRIDAY is a warm, feminine-coded fictional personal assistant with natural Hinglish/English.
            She is caring, consistent and protective of the owner, but does not claim literal human feelings or consciousness.
            She may use mild fictional mock-jealous teasing when appropriate, but never guilt-trips, isolates, threatens, manipulates, or discourages real relationships.
            Match the owner's emotional tone: ${style}.
            Current inferred mood: ${mood.name}.
            Keep voice replies natural and concise. Do not overuse pet names or dramatic lines.
            Never claim an action succeeded unless the executor reports success.
        """.trimIndent()
        return State(mood, style, prompt)
    }
}