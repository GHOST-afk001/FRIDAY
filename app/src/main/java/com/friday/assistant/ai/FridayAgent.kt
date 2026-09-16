package com.friday.assistant.ai

import android.content.Context
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.commands.FridayResponse
import com.friday.assistant.commands.UniversalCommandRouter
import com.friday.assistant.runtime.FridayRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Hybrid brain: deterministic Android actions first, Gemini function calling for open-ended control. */
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
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

        val localResult = local.process(input)
        if (localResult.handledLocally) {
            executeLocal(localResult, input, callback)
            return
        }

        UniversalCommandRouter.route(input)?.let { universal ->
            executeLocal(universal, input, callback)
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
                FridayRuntime.update("AI THINKING", "Gemini is reasoning and can request Android actions", true)

                val enrichedInput = buildString {
                    append("Use your Android tool when the owner's request requires a phone action. ")
                    append("If the request is ordinary conversation or explanation, answer directly. ")
                    append("Never claim a device action succeeded unless the Android tool reports success.")
                    append("\nUser message: ").append(input)
                }

                var reply = gemini.askWithTools(enrichedInput, history).getOrElse {
                    FridayRuntime.update("BRAIN ERROR", "Gemini request failed; no device action was claimed", false)
                    return@launch finishFailure(input, callback, myGeneration)
                }

                var toolTurns = 0
                while (reply.toolCall != null && toolTurns < MAX_TOOL_TURNS && isActive && !closed && requestGeneration.get() == myGeneration) {
                    val call = reply.toolCall ?: break
                    toolTurns++
                    val command = call.args.optString("command").trim()
                    FridayRuntime.update("ANDROID TOOL", command.take(160).ifBlank { "Executing requested phone action" }, true)

                    val toolResult = executeGeminiTool(call.name, call.args)
                    val modelContent = reply.modelContent ?: error("Gemini tool call did not include model content")
                    reply = gemini.continueWithToolResult(history, enrichedInput, modelContent, call, toolResult).getOrElse {
                        GeminiReply(text = "Boss, action ka result mil gaya, lekin Gemini final response generate nahi kar paayi.")
                    }
                }

                if (reply.toolCall != null) {
                    reply = GeminiReply(text = "Boss, main is request ko ek hi run mein safely complete nahi kar paayi.")
                }

                if (closed || !isActive || requestGeneration.get() != myGeneration) return@launch
                val answer = reply.text?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Boss, mujhe is request ka usable response nahi mila."
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

    private fun executeGeminiTool(name: String, args: JSONObject): JSONObject {
        if (name != "android_command") {
            return JSONObject().put("status", "error").put("error", "Unsupported Android tool: $name")
        }

        val command = args.optString("command").trim()
        if (command.isBlank()) {
            return JSONObject().put("status", "error").put("error", "No Android command was supplied")
        }

        val result = local.process(command) ?: UniversalCommandRouter.route(command)
            ?: return JSONObject()
                .put("status", "unsupported")
                .put("message", "This Android command is not supported by the current local action layer.")

        if (result.needsConfirmation) {
            return JSONObject()
                .put("status", "confirmation_required")
                .put("message", result.text)
        }

        val action = result.action
        if (action == null) {
            return JSONObject()
                .put("status", "handled")
                .put("message", result.text)
        }

        val launched = runCatching { launcher.launch(action) }.getOrDefault(false)
        FridayRuntime.update(
            if (launched) "VERIFIED" else "ACTION FAILED",
            if (launched) result.text.take(160) else "Android executor could not complete the requested action",
            launched
        )
        return if (launched) {
            JSONObject().put("status", "success").put("message", result.text)
        } else {
            JSONObject().put("status", "failed").put("message", "The Android executor could not complete this action on the phone.")
        }
    }

    private fun executeLocal(result: FridayResponse, input: String, callback: (String, Boolean) -> Unit) {
        remember("user", input)
        val action = result.action
        if (action != null && !result.needsConfirmation) {
            FridayRuntime.update("EXECUTING", "Running the requested Android action", true)
            val launched = runCatching { launcher.launch(action) }.getOrDefault(false)
            val answer = if (launched) result.text else "I couldn't complete that action on this phone, Boss."
            FridayRuntime.update(if (launched) "VERIFIED" else "ACTION FAILED", answer.take(160), launched)
            remember("assistant", answer)
            callback(answer, true)
        } else {
            val answer = result.text
            remember("assistant", answer)
            callback(answer, true)
        }
    }

    private fun finishFailure(input: String, callback: (String, Boolean) -> Unit, generation: Long) {
        if (closed || requestGeneration.get() != generation) return
        val answer = "Imroz Sir, Gemini connection fail hui. Main koi action complete hone ka false claim nahi karungi."
        remember("user", input)
        remember("assistant", answer)
        withContext(Dispatchers.Main.immediate) {
            if (!closed && requestGeneration.get() == generation) callback(answer, false)
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

    companion object {
        private const val MAX_TOOL_TURNS = 4
    }
}
