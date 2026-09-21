package com.friday.assistant.ai

import android.content.Context
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.commands.FridayResponse
import com.friday.assistant.commands.UniversalCommandRouter
import com.friday.assistant.runtime.FridayMemory
import com.friday.assistant.runtime.FridayNotifications
import com.friday.assistant.runtime.FridayNotificationReply
import com.friday.assistant.runtime.FridayRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private val memory = FridayMemory(appContext)
    private val brainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestGeneration = AtomicLong(0L)
    @Volatile private var closed = false
    @Volatile private var activeRequest: Job? = null

    fun configureApiKey(key: String): Boolean = !closed && gemini.setApiKey(key)
    fun hasApiKey() = !closed && gemini.isConfigured()
    fun clearApiKey() { if (!closed) gemini.clearApiKey() }

    fun verifyGemini(callback: (Boolean, String) -> Unit) {
        if (closed) return
        brainScope.launch {
            val result = gemini.testConnection()
            val message = result.getOrElse { it.message ?: "Gemini connection failed" }
            withContext(Dispatchers.Main.immediate) {
                if (!closed) callback(result.isSuccess, message)
            }
        }
    }

    fun handle(input: String, callback: (String, Boolean) -> Unit) {
        if (closed) return
        FridayRuntime.update("UNDERSTANDING", "Checking local Android commands", true)

        handleRememberRequest(input)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

        handleFavoriteSong(input)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

        handleNotificationReply(input)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

        handleNotificationQuery(input)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

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
                    append(memory.contextForBrain()).append("\n")
                    append("Current notification context: ").append(FridayNotifications.describe()).append("\n")
                    append("Use your Android tool when the owner's request requires a phone action. ")
                    append("If the request is ordinary conversation or explanation, answer directly. ")
                    append("Never claim a device action succeeded unless the Android tool reports success.")
                    append("\nUser message: ").append(input)
                }

                var requestResult = gemini.askWithTools(enrichedInput, history)
                var retry = 0
                while (requestResult.isFailure && retry < 2 && isActive && !closed) {
                    retry++
                    delay(700L * retry)
                    requestResult = gemini.askWithTools(enrichedInput, history)
                }
                var reply = requestResult.getOrElse {
                    FridayRuntime.update("BRAIN ERROR", it.message ?: "Gemini request failed; no device action was claimed", false)
                    finishFailure(input, callback, myGeneration)
                    return@launch
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

        val localResult = local.process(command)
        val result = if (localResult.handledLocally) localResult else UniversalCommandRouter.route(command)
        if (result == null || !result.handledLocally) {
            return JSONObject()
                .put("status", "unsupported")
                .put("message", "This Android command is not supported by the current local action layer.")
        }

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
        // A spoken/typed command is already an explicit user instruction. Calls are
        // executed here after the normal contact/permission checks; emergency actions
        // remain confirmation-gated by the parser.
        val explicitCall = action is com.friday.assistant.commands.FridayAction.DialContact ||
            action is com.friday.assistant.commands.FridayAction.DialNumber
        if (action != null && (!result.needsConfirmation || explicitCall)) {
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

    private suspend fun finishFailure(input: String, callback: (String, Boolean) -> Unit, generation: Long) {
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
            return "Aap " + memory.ownerName() + " Sir hain. Main FRIDAY hoon, aapki personal AI assistant."
        }
        val match = Regex("^(?:my name is|mera naam|mera name|call me)\\s+([\\p{L}][\\p{L} .'-]{1,29})(?:\\s+hai)?[.!]?$", RegexOption.IGNORE_CASE).find(value) ?: return null
        val name = match.groupValues[1].trim().replace(Regex("\\s+hai$", RegexOption.IGNORE_CASE), "").trim()
        if (name.isBlank()) return null
        memory.setOwnerName(name)
        return "Understood, " + name + " Sir. Main aapka naam yaad rakhungi."
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

    private fun handleNotificationReply(input: String): String? {
        val lower = input.trim().lowercase(Locale.ROOT)
        val replyIntent = lower.contains("reply") || lower.contains("jawab") ||
            (lower.contains("message") && lower.contains("usko"))
        if (!replyIntent) return null
        val target = FridayNotifications.latest()
            ?: return "Boss, mujhe reply karne ke liye koi recent notification nahi mil rahi."
        val message = listOf(
            Regex("(?:reply|jawab)(?:\\s+(?:kardo|kar do|do))?\\s+(?:usko|him|her|them)\\s+(?:ki|that|bolo|bol do)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("(?:message|msg)\\s+(?:usko|him|her|them)\\s+(?:ki|that|bolo|bol do)\\s+(.+)$", RegexOption.IGNORE_CASE),
            Regex("(?:reply|jawab)\\s+(?:with|mein)\\s+(.+)$", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(input.trim())?.groupValues?.get(1)?.trim()?.removeSuffix(".") }
            ?: return "Boss, " + target.title.ifBlank { target.app } + " ko kya reply bhejun?"
        val sent = FridayNotificationReply.send(target, message)
        return if (sent) "Done Boss. Maine " + target.title.ifBlank { target.app } + " ko reply bhej diya."
        else "Boss, is notification mein direct reply action available nahi hai. Accessibility access enabled ho to main UI automation fallback use kar sakti hoon."
    }

    private fun handleNotificationQuery(input: String): String? {
        val lower = input.trim().lowercase(Locale.ROOT)
        val asks = lower.contains("notification") || lower.contains("message aaya") || lower.contains("msg aaya") ||
            lower.contains("kisne message") || lower.contains("who messaged")
        if (!asks) return null
        val query = Regex("(?:from|se|ka|ki|ke)\\s+([\\p{L}\\p{M}][\\p{L}\\p{M} ]{0,35})", RegexOption.IGNORE_CASE)
            .find(input)?.groupValues?.get(1)?.trim()
        return FridayNotifications.describe(query).let { result ->
            if (result == "There are no recent notifications.") "Boss, abhi koi recent notification nahi hai." else result
        }
    }

    private fun handleFavoriteSong(input: String): String? {
        val value = input.trim()
        val save = Regex("^(?:my|mera|meri)\\s+(?:favorite|favourite)\\s+(?:song|gaana|gana)\\s+(?:is|hai)\\s+(.+)$", RegexOption.IGNORE_CASE).find(value)
        if (save != null) {
            val song = save.groupValues[1].trim().removeSuffix(".")
            if (song.isBlank()) return null
            return if (memory.rememberFact("favorite song: $song")) {
                "Yaad rakh liya Boss. Aapka favorite song $song hai."
            } else {
                "Boss, main is preference ko save nahi kar paayi."
            }
        }
        val asks = Regex("^(?:what(?:'s| is)\\s+my|mera)\\s+(?:favorite|favourite)\\s+(?:song|gaana|gana)\\??$", RegexOption.IGNORE_CASE).matches(value)
        if (!asks) return null
        val fact = memory.facts().lastOrNull { it.lowercase(Locale.ROOT).startsWith("favorite song:") }
        return if (fact != null) {
            "Boss, aapka favorite song ${fact.substringAfter(":").trim()} hai."
        } else {
            "Boss, aapne abhi tak mujhe favorite song nahi bataya. Aap bata denge toh main yaad rakhungi."
        }
    }

    private fun handleRememberRequest(input: String): String? {
        val match = Regex("^(?:remember|please remember|yaad rakhna|yaad rakh|yaad rakh lo|save this)\\s*[:,-]?\\s*(.+)$", RegexOption.IGNORE_CASE).find(input.trim())
            ?: return null
        val fact = match.groupValues[1].trim().removeSuffix(".")
        return if (memory.rememberFact(fact)) {
            "Done Boss. Main ise apni persistent memory mein yaad rakhungi."
        } else {
            "Boss, main passwords, OTPs ya API keys ko memory mein save nahi karungi."
        }
    }

    private fun remember(role: String, text: String) {
        memory.rememberConversation(role, text)
    }

    private fun loadHistory(): List<Pair<String, String>> = memory.history()


    companion object {
        private const val MAX_TOOL_TURNS = 4
    }
}
