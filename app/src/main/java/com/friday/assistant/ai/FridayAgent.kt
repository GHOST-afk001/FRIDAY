package com.friday.assistant.ai

import android.content.Context
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.runtime.FridayRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** FRIDAY's hybrid brain: deterministic local Android commands first, Gemini for open-ended intelligence. */
class FridayAgent(context: Context) {
    private val appContext = context.applicationContext
    private val local = FridayCommandProcessor()
    private val launcher = AppLauncher(appContext)
    private val gemini = GeminiProvider(appContext)
    private val historyPrefs = appContext.getSharedPreferences("friday_memory", Context.MODE_PRIVATE)
    private val profilePrefs = appContext.getSharedPreferences("friday_profile", Context.MODE_PRIVATE)
    private val brainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryLock = Any()
    private val requestGeneration = AtomicLong(0L)
    @Volatile private var closed = false
    @Volatile private var activeRequest: Job? = null

    fun configureApiKey(key: String) { if (!closed) gemini.setApiKey(key) }
    fun hasApiKey() = !closed && gemini.isConfigured()
    fun clearApiKey() { if (!closed) gemini.clearApiKey() }

    fun handle(input: String, callback: (String, Boolean) -> Unit) {
        if (closed) return
        FridayRuntime.update("UNDERSTANDING", "Checking local Android commands", true)

        handleIdentity(input)?.let { answer ->
            remember("user", input); remember("assistant", answer); callback(answer, true); return
        }

        val localResult = local.process(input)
        if (localResult.handledLocally) {
            remember("user", input)
            if (localResult.action != null && !localResult.needsConfirmation) {
                FridayRuntime.update("EXECUTING", "Running the requested Android action", true)
                val launched = runCatching { launcher.launch(localResult.action) }.getOrDefault(false)
                val answer = if (launched) localResult.text else "I couldn't complete that action on this phone, Boss."
                FridayRuntime.update(if (launched) "VERIFIED" else "ACTION FAILED", answer.take(120), launched)
                remember("assistant", answer)
                callback(answer, true)
            } else {
                remember("assistant", localResult.text)
                callback(localResult.text, true)
            }
            return
        }

        if (!gemini.isConfigured()) {
            val answer = "Boss, Gemini brain abhi configured nahi hai. Gemini API key add kijiye; uske baad main open-ended requests handle karungi."
            FridayRuntime.update("BRAIN NOT CONFIGURED", "Gemini API key is required for open-ended intelligence", false)
            callback(answer, false)
            return
        }

        val history = loadHistory()
        val myGeneration = requestGeneration.incrementAndGet()
        activeRequest?.cancel()
        gemini.cancel()
        activeRequest = brainScope.launch {
            try {
                if (closed || !isActive || requestGeneration.get() != myGeneration) return@launch
                FridayRuntime.update("AI THINKING", "Gemini is reasoning over your request", true)
                val enrichedInput = buildString {
                    append("You are FRIDAY, a personal Android AI assistant. Address the owner as Imroz Sir or Boss. ")
                    append("Use Hindi, Hinglish, or English naturally based on the user's wording. ")
                    append("This is an open-ended request because deterministic local command handling did not match it. ")
                    append("Do not claim an Android action was performed unless the local action system explicitly reported success.")
                    append("\nUser message: ").append(input)
                }
                val result = gemini.ask(enrichedInput, history)
                if (closed || !isActive || requestGeneration.get() != myGeneration) return@launch
                val answer = result.getOrElse {
                    FridayRuntime.update("BRAIN ERROR", "Gemini request failed; no device action was claimed", false)
                    "Imroz Sir, Gemini connection fail hui. Main koi action complete hone ka false claim nahi karungi."
                }
                remember("user", input)
                remember("assistant", answer)
                withContext(Dispatchers.Main.immediate) {
                    if (!closed && requestGeneration.get() == myGeneration) {
                        FridayRuntime.update("RESPONSE READY", "Gemini response ready for speech", true)
                        callback(answer, false)
                    }
                }
            } finally {
                if (requestGeneration.get() == myGeneration) activeRequest = null
            }
        }
    }

    private fun handleIdentity(input: String): String? {
        val value = input.trim()
        val lower = value.lowercase(Locale.ROOT)
        if (lower.matches(Regex("(?:what is|what's|whats) my name\\??")) || lower in setOf("mera naam kya hai", "mera name kya hai", "main kaun hoon", "who am i")) {
            return "Aap Imroz Sir hain. Main FRIDAY hoon, aapki personal AI assistant."
        }
        val match = Regex("^(?:my name is|mera naam|mera name|call me)\\s+([\\p{L}][\\p{L} .'-]{1,29})(?:\\s+hai)?[.!]?$", RegexOption.IGNORE_CASE).find(value) ?: return null
        val name = match.groupValues[1].trim().replace(Regex("\\s+hai$", RegexOption.IGNORE_CASE), "").trim()
        if (name.isBlank()) return null
        profilePrefs.edit().putString("owner_name", name).apply()
        return "Understood, Imroz Sir. Main aapka naam yaad rakhungi."
    }

    fun close() {
        if (closed) return
        closed = true
        requestGeneration.incrementAndGet()
        activeRequest?.cancel()
        activeRequest = null
        gemini.cancel()
        brainScope.cancel()
        FridayRuntime.update("IDLE", "FRIDAY brain stopped", true)
    }

    private fun remember(role: String, text: String) {
        synchronized(memoryLock) {
            val items = loadHistoryLocked().toMutableList()
            items.add(role to text.take(1200))
            val trimmed = items.takeLast(30)
            val encoded = trimmed.joinToString("\n") { "${it.first}|${it.second.replace("\n", " ")}" }
            historyPrefs.edit().putString("history", encoded).apply()
        }
    }

    private fun loadHistory(): List<Pair<String, String>> = synchronized(memoryLock) { loadHistoryLocked() }

    private fun loadHistoryLocked(): List<Pair<String, String>> = historyPrefs.getString("history", "").orEmpty().lineSequence().mapNotNull {
        val p = it.indexOf('|')
        if (p <= 0) null else it.substring(0, p) to it.substring(p + 1)
    }.toList()
}
