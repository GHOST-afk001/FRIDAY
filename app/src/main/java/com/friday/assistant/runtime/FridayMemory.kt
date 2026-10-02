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

    fun ownerName(): String = prefs.getString(KEY_OWNER_NAME, DEFAULT_OWNER_NAME).orEmpty().ifBlank { DEFAULT_OWNER_NAME }

    fun setOwnerName(name: String) {
        val clean = name.trim().take(60)
        if (clean.isBlank()) return
        prefs.edit().putString(KEY_OWNER_NAME, clean).apply()
    }

    fun rememberFact(fact: String): Boolean {
        val clean = fact.replace(Regex("\\s+"), " ").trim().take(MAX_FACT)
        if (clean.isBlank() || looksLikeSecret(clean)) return false
        synchronized(lock) {
            val facts = readJsonArray(KEY_FACTS)
            val normalized = clean.lowercase(Locale.ROOT)
            for (i in 0 until facts.length()) {
                if (facts.optString(i).lowercase(Locale.ROOT) == normalized) return true
            }
            facts.put(clean)
            while (facts.length() > MAX_FACTS) facts.remove(0)
            prefs.edit().putString(KEY_FACTS, facts.toString()).apply()
        }
        return true
    }

    fun learnFromUserUtterance(text: String) {
        val value = text.replace(Regex("\\s+"), " ").trim()
        if (value.isBlank()) return
        val candidates = listOf(
            Regex("^(?:i|main)\\s+(?:like|love|prefer|pasand karta hoon|pasand hai)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:mujhe|i)\\s+(?:pasand|favorite|favourite)\\s+(?:hai|is)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:my favorite|my favourite)\\s+(.+?)\\s+(?:is|hai)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        candidates.firstNotNullOfOrNull { it.find(value) }?.let { match ->
            val fact = if (match.groupValues.size > 2 && match.groupValues[2].isNotBlank())
                "Favorite " + match.groupValues[1].trim() + " is " + match.groupValues[2].trim()
            else "Preference: " + match.groupValues[1].trim()
            rememberFact(fact)
        }
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
        if (clean.isBlank() || looksLikeSecret(clean)) return
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

    fun contextForBrain(): String {
        val name = ownerName()
        val facts = facts()
        val recent = history().takeLast(12)
        return buildString {
            append("Persistent FRIDAY memory:\n")
            append("- Owner name: ").append(name).append("\n")
            append("- Current mode: ").append(mode()).append("\n")
            if (facts.isNotEmpty()) {
                append("- Remembered facts/preferences:\n")
                facts.forEach { append("  - ").append(it).append("\n") }
            }
            val savedNotes = notes().takeLast(8)
            if (savedNotes.isNotEmpty()) {
                append("- Saved notes:\n")
                savedNotes.forEach { append("  - ").append(it).append("\n") }
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
            lower.contains("otp") || lower.contains("one time password") ||
            lower.contains("authorization: bearer") || lower.contains("bearer ") ||
            Regex("""(?i)(?:AIza[0-9A-Za-z_-]{20,}|gsk_[0-9A-Za-z_-]{20,}|sk-[0-9A-Za-z_-]{20,}|api[_-]?key\s*[=:])""").containsMatchIn(value)
    }

    fun setMode(mode: String) {
        val clean = mode.trim().lowercase(Locale.ROOT).take(30)
        if (clean.isBlank()) return
        prefs.edit().putString(KEY_MODE, clean).apply()
    }

    fun mode(): String = prefs.getString(KEY_MODE, "normal").orEmpty().ifBlank { "normal" }

    fun addNote(note: String): Boolean {
        val clean = note.replace(Regex("\\s+"), " ").trim().take(MAX_NOTE)
        if (clean.isBlank() || looksLikeSecret(clean)) return false
        synchronized(lock) {
            val notes = readJsonArray(KEY_NOTES)
            notes.put(JSONObject().put("text", clean).put("time", System.currentTimeMillis()))
            while (notes.length() > MAX_NOTES) notes.remove(0)
            prefs.edit().putString(KEY_NOTES, notes.toString()).apply()
        }
        return true
    }

    fun notes(): List<String> = synchronized(lock) {
        val notes = readJsonArray(KEY_NOTES)
        (0 until notes.length()).mapNotNull { notes.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
    }

    fun clearNotes() { prefs.edit().remove(KEY_NOTES).apply() }

    companion object {
        private const val PREFS = "friday_persistent_memory"
        private const val KEY_OWNER_NAME = "owner_name"
        private const val KEY_FACTS = "facts"
        private const val KEY_HISTORY = "history"
        private const val KEY_MODE = "mode"
        private const val KEY_NOTES = "notes"
        private const val DEFAULT_OWNER_NAME = "Boss"
        private const val MAX_FACTS = 80
        private const val MAX_HISTORY = 120
        private const val MAX_FACT = 500
        private const val MAX_MESSAGE = 1200
        private const val MAX_CONTEXT = 18_000
        private const val MAX_NOTES = 100
        private const val MAX_NOTE = 800
    }
}
