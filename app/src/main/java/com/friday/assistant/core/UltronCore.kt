package com.friday.assistant.core

/** Platform-neutral personality and reasoning context for FRIDAY.
 *  Device adapters should execute only typed actions produced outside this core.
 */
class UltronCore {
    fun prepare(input: String, history: List<Pair<String, String>>): CoreContext {
        val emotion = detectEmotion(input)
        val tone = when (emotion) {
            Emotion.BORED -> "warm, engaging, conversational; proactively offer a small interesting activity"
            Emotion.SAD -> "gentle, patient, supportive; invite conversation without forcing it"
            Emotion.ANGRY -> "calm, non-judgmental, concise; help reduce friction"
            Emotion.EXCITED -> "energetic and playful while staying useful"
            Emotion.STRESSED -> "calm, reassuring, structured; reduce cognitive load"
            Emotion.CASUAL -> "friendly, natural and conversational"
        }
        return CoreContext(
            emotion = emotion,
            systemGuidance = "You are FRIDAY. Your intelligence is strategic and precise, inspired by a futuristic AI core. Your voice and behavior are warm, feminine, natural, and personal. Address the user as Boss when natural. Match Hindi, Hinglish, or English. $tone. If the user says they are bored or simply wants company, talk with them instead of forcing a task. Never claim a device action happened unless the Android tool layer confirms it."
        )
    }

    private fun detectEmotion(text: String): Emotion {
        val value = text.lowercase()
        return when {
            value.containsAny("bore", "boring", "mann nahi", "बोर", "ऊब") -> Emotion.BORED
            value.containsAny("sad", "dukhi", "udaas", "akela", "अकेला", "दुखी", "उदास") -> Emotion.SAD
            value.containsAny("gussa", "angry", "irritat", "गुस्सा", "परेशान") -> Emotion.ANGRY
            value.containsAny("excited", "khush", "happy", "मज़ा", "खुश") -> Emotion.EXCITED
            value.containsAny("stress", "tension", "pareshan", "तनाव", "टेंशन") -> Emotion.STRESSED
            else -> Emotion.CASUAL
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it, ignoreCase = true) }
}

data class CoreContext(
    val emotion: Emotion,
    val systemGuidance: String
)

enum class Emotion { CASUAL, BORED, SAD, ANGRY, EXCITED, STRESSED }
