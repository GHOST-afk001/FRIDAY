package com.friday.assistant.core

/**
 * Platform-neutral intent understanding. It extracts only high-confidence signals from
 * user text; the online model remains responsible for nuanced language understanding.
 * No Android APIs, network calls, or device actions belong here.
 */
data class IntentUnderstanding(
    val category: IntentCategory,
    val confidence: Float,
    val entities: Map<String, String>,
    val missing: List<String>,
    val needsClarification: Boolean
)

enum class IntentCategory {
    CONVERSATION,
    INFORMATION,
    PLANNING,
    EXPLANATION,
    COMPARISON,
    NAVIGATION,
    COMMUNICATION,
    DEVICE_ACTION,
    SAFETY,
    CREATIVE,
    UNKNOWN
}

class IntentUnderstandingEngine {
    fun understand(input: String, history: List<Pair<String, String>>): IntentUnderstanding {
        val raw = input.trim()
        val value = raw.lowercase()
        if (value.isBlank()) return IntentUnderstanding(IntentCategory.UNKNOWN, 0f, emptyMap(), listOf("request"), true)

        val category = when {
            value.containsAny("danger", "emergency", "khatre", "khatra", "help me", "madad karo", "112") -> IntentCategory.SAFETY
            value.containsAny("call", "phone karo", "message", "sms", "text karo", "whatsapp") -> IntentCategory.COMMUNICATION
            value.containsAny("maps", "route", "raasta", "navigate", "location dikhao") -> IntentCategory.NAVIGATION
            value.containsAny("open", "kholo", "band", "volume", "flashlight", "torch", "alarm", "timer", "settings") -> IntentCategory.DEVICE_ACTION
            value.containsAny("compare", "comparison", "difference", "better", "vs", "versus") -> IntentCategory.COMPARISON
            value.containsAny("how", "kaise", "kyu", "why", "explain", "samjhao", "meaning") -> IntentCategory.EXPLANATION
            value.containsAny("plan", "planning", "schedule", "suggest", "kya karu", "help me decide") -> IntentCategory.PLANNING
            value.containsAny("write", "likho", "draft", "story", "poem", "email", "application", "caption") -> IntentCategory.CREATIVE
            value.containsAny("bore", "boring", "mann nahi", "lonely", "baat karo", "talk to me", "kaise ho") -> IntentCategory.CONVERSATION
            value.endsWith("?") || value.containsAny("what", "kya", "kab", "where", "kahaan", "who", "kaun") -> IntentCategory.INFORMATION
            else -> IntentCategory.UNKNOWN
        }

        val entities = buildMap {
            extractAfter(value, "call", "phone", "message", "sms", "whatsapp")?.let { put("target", it) }
            extractAfter(value, "maps", "route", "navigate", "location")?.let { put("destination", it) }
            Regex("\\b(?:in|at|on|mein|par)\\s+(.+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.let {
                if (it.length <= 100) put("context", it.trim())
            }
        }

        val missing = when (category) {
            IntentCategory.COMMUNICATION -> if (entities["target"].isNullOrBlank()) listOf("recipient") else emptyList()
            IntentCategory.NAVIGATION -> if (entities["destination"].isNullOrBlank()) listOf("destination") else emptyList()
            IntentCategory.DEVICE_ACTION -> if (value.containsAny("alarm", "timer") && !Regex("\\d+").containsMatchIn(value)) listOf("time_or_duration") else emptyList()
            else -> emptyList()
        }

        val baseConfidence = when (category) {
            IntentCategory.UNKNOWN -> 0.35f
            IntentCategory.SAFETY, IntentCategory.COMMUNICATION, IntentCategory.NAVIGATION, IntentCategory.DEVICE_ACTION -> 0.90f
            IntentCategory.CONVERSATION, IntentCategory.INFORMATION, IntentCategory.EXPLANATION, IntentCategory.PLANNING, IntentCategory.COMPARISON, IntentCategory.CREATIVE -> 0.78f
        }
        val contextBoost = if (history.isNotEmpty() && category == IntentCategory.UNKNOWN) 0.08f else 0f
        return IntentUnderstanding(category, (baseConfidence + contextBoost).coerceAtMost(0.98f), entities, missing, missing.isNotEmpty())
    }

    private fun extractAfter(value: String, vararg markers: String): String? {
        val marker = markers.firstOrNull { value.contains(it) } ?: return null
        val index = value.indexOf(marker)
        if (index < 0) return null
        val tail = value.substring(index + marker.length).trim(' ', ',', ':', '-', '—')
        return tail.takeIf { it.isNotBlank() && it.length <= 100 }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it, ignoreCase = true) }
}
