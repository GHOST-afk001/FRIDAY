package com.friday.assistant.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent local memory for FRIDAY.
 *
 * Memory is stored on the phone in app-private SharedPreferences. It survives
 * process death and app restarts, is bounded, and never stores API keys.
 */
class FridayMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun ownerName(): String = prefs.getString(KEY_OWNER_NAME, "Imroz").orEmpty().ifBlank { "Imroz" }

    fun setOwnerName(name: String) {
        val clean = name.trim().take(60)
        if (clean.isBlank()) return
        prefs.edit().putString(KEY_OWNER_NAME, clean).apply()
    }

    /** Stores a durable user fact/preference that should survive future conversations. */
    fun rememberFact(fact: String): Boolean {
        val clean = fact.replace(Regex("\\s+"), " ").trim().take(MAX_FACT)
        if (clean.isBlank() || looksLikeSecret(clean)) return false
        synchronized(lock) {
            val facts = readJsonArray(KEY_FACTS)
            val normalized = clean.lowercase(Locale.ROOT)
            for (i in 0 until facts.length()) {
                if (facts.optString(i).lowercase(Locale.ROOT) == normalized) {
                    return true
                }
            }
            facts.put(clean)
            while (facts.length() > MAX_FACTS) facts.remove(0)
            prefs.edit().putString(KEY_FACTS, facts.toString()).apply()
        }
        return true
    }

    fun forgetFacts() {
        prefs.edit().remove(KEY_FACTS).apply()
    }

    fun facts(): List<String> = synchronized(lock) {
        val facts = readJsonArray(KEY_FACTS)
        (0 until facts.length()).mapNotNull { facts.optString(it).takeIf(String::isNotBlank) }
    }

    fun rememberConversation(role: String, text: String) {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(MAX_MESSAGE)
        if (clean.isBlank()) return
        synchronized(lock) {
            val history = readJsonArray(KEY_HISTORY)
            history.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("text", clean).put("time", System.currentTimeMillis()))
            while (history.length() > MAX_HISTORY) history.remove(0)
            prefs.edit().putString(KEY_HISTORY, history.toString()).apply()
        }
    }

    fun history(): List<Pair<String, String>> = synchronized(lock) {
        val history = readJsonArray(KEY_HISTORY)
        (0 until history.length()).mapNotNull { i ->
            val item = history.optJSONObject(i) ?: return@mapNotNull null
            val role = item.optString("role").ifBlank { return@mapNotNull null }
            val text = item.optString("text").ifBlank { return@mapNotNull null }
            role to text
        }
    }

    /** Compact durable context injected into Gemini on every new request. */
    fun contextForBrain(): String {
        val name = ownerName()
        val facts = facts()
        val recent = history().takeLast(12)
        return buildString {
            append("Persistent FRIDAY memory:\n")
            append("- Owner name: ").append(name).append("\n")
            if (facts.isNotEmpty()) {
                append("- Remembered facts/preferences:\n")
                facts.forEach { append("  - ").append(it).append("\n") }
            }
            if (recent.isNotEmpty()) {
                append("- Recent conversation context:\n")
                recent.forEach { (role, text) ->
                    append("  ").append(role).append(": ").append(text.take(600)).append("\n")
                }
            }
        }.take(MAX_CONTEXT)
    }

    fun clearConversationHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun readJsonArray(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrElse { JSONArray() }

    private fun looksLikeSecret(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains("api key") || lower.contains("apikey") ||
            lower.contains("password") || lower.contains("passcode") ||
            lower.contains("otp") || lower.contains("one time password")
    }

    companion object {
        private const val PREFS = "friday_persistent_memory"
        private const val KEY_OWNER_NAME = "owner_name"
        private const val KEY_FACTS = "facts"
        private const val KEY_HISTORY = "history"
        private const val MAX_FACTS = 80
        private const val MAX_HISTORY = 120
        private const val MAX_FACT = 500
        private const val MAX_MESSAGE = 1200
        private const val MAX_CONTEXT = 18_000
    }
}
