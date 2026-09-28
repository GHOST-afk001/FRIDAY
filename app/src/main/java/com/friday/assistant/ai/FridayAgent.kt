package com.friday.assistant.ai

import android.content.Context
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.commands.FridayAction
import com.friday.assistant.commands.FridayResponse
import com.friday.assistant.commands.UniversalCommandRouter
import com.friday.assistant.runtime.FridayMemory
import com.friday.assistant.runtime.FridayCapabilities
import com.friday.assistant.runtime.FridayBehaviorEngine
import com.friday.assistant.runtime.FridaySafetyPolicy
import com.friday.assistant.runtime.FridayRoutineStore
import com.friday.assistant.runtime.FridayNotifications
import com.friday.assistant.runtime.FridayNotificationReply
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.weather.FridayWeatherService
import com.friday.assistant.integrations.RestCountriesService
import com.friday.assistant.integrations.IpInfoService
import com.friday.assistant.integrations.FridayLocalMoodService
import com.friday.assistant.integrations.FridayRssNewsService
import com.friday.assistant.ai.SecureApiKeyStore
import java.time.LocalDate
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
    private val groq = GroqProvider(appContext)
    private val openRouter = OpenRouterProvider(appContext)
    private val countries = RestCountriesService()
    private val ipInfo = IpInfoService(appContext)
    private val keyStore = SecureApiKeyStore(appContext)
    private val emotion = FridayLocalMoodService()
    private val news = FridayRssNewsService()
    private val memory = FridayMemory(appContext)
    private val capabilities = FridayCapabilities(appContext)
    private val behavior = FridayBehaviorEngine()
    private val routines = FridayRoutineStore(appContext)
    private val brainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestGeneration = AtomicLong(0L)
    @Volatile private var closed = false
    @Volatile private var activeRequest: Job? = null
    init {
        // Remove credentials for integrations intentionally retired from FRIDAY.
    }

    fun configureApiKey(key: String) { if (!closed) gemini.setApiKey(key) }
    fun configureGroqKey(key: String) { if (!closed) groq.configureApiKey(key) }
    fun hasGroqKey() = !closed && groq.isConfigured()
    fun configureOpenRouterKey(key: String) { if (!closed) openRouter.configureApiKey(key) }
    fun configureIpInfoToken(token: String) { if (!closed) ipInfo.configureToken(token) }
    fun hasApiKey() = !closed && gemini.isConfigured()
    fun hasOpenRouterKey() = !closed && openRouter.isConfigured()
    fun clearApiKey() { if (!closed) gemini.clearApiKey() }
    fun clearGroqKey() { if (!closed) groq.clearApiKey() }
    fun configurePollinationsKey(key: String) { if (!closed) keyStore.saveNamed("pollinations", key) }
    fun hasPollinationsKey() = !closed && !keyStore.readNamed("pollinations").isNullOrBlank()

    fun handle(input: String, callback: (String, Boolean) -> Unit) {
        if (closed) return
        FridayRuntime.update("UNDERSTANDING", "Checking local Android commands", true)
        memory.learnFromUserUtterance(input)

        handleRememberRequest(input)?.let { answer ->
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

        handleRoutineCommand(input)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

        capabilities.handle(input)?.let { result ->
            executeLocal(result, input, callback)
            return
        }

        handleNotificationQuery(input)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            callback(answer, true)
            return
        }

        if (isCountryOrLocationRequest(input)) {
            val generation = requestGeneration.incrementAndGet()
            activeRequest?.cancel()
            activeRequest = brainScope.launch {
                val answer = runCatching { handleCountryOrLocation(input) }
                    .getOrNull()
                    ?: "Boss, information service abhi available nahi hai. Main guess nahi karungi."
                if (closed || requestGeneration.get() != generation) return@launch
                remember("user", input)
                remember("assistant", answer)
                withContext(Dispatchers.Main.immediate) {
                    if (!closed && requestGeneration.get() == generation) callback(answer, true)
                }
                if (requestGeneration.get() == generation) activeRequest = null
            }
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

        if (!gemini.isConfigured() && !groq.isConfigured() && !openRouter.isConfigured()) {
            val answer = "Boss, kam se kam ek AI brain configure karna hoga: Gemini, Groq ya OpenRouter."
            FridayRuntime.update("BRAIN NOT CONFIGURED", "No AI provider is configured", false)
            callback(answer, false)
            return
        }

        val history = loadHistory()
        val myGeneration = requestGeneration.incrementAndGet()
        activeRequest?.cancel()
        gemini.cancel()
        groq.cancel()
        openRouter.cancel()
        activeRequest = brainScope.launch {
            try {
                if (closed || !isActive || requestGeneration.get() != myGeneration) return@launch
                FridayRuntime.update("AI THINKING", "Gemini is reasoning and can request Android actions", true)

                val behaviorState = behavior.analyze(input, memory.mode())
                val moodContext = runCatching { emotion.analyze(input) }.getOrDefault("neutral")
                val enrichedInput = buildString {
                    append(memory.contextForBrain()).append("\n")
                    append("Current notification context: ").append(FridayNotifications.describe()).append("\n")
                    append(behaviorState.systemPrompt).append("\n")
                    if (moodContext.isNotBlank()) append("Detected emotional context (use gently, never overstate): ").append(moodContext).append("\n")
                    append("Execution style: explicit user requests authorize ordinary, reversible phone actions; do not repeatedly ask for confirmation for those actions. ")
                    append("Calls, SMS, emergency actions, purchases/payments, destructive actions, and risky external submissions still require explicit confirmation. ")
                    append("Use your Android tool when the owner's request requires a phone action. ")
                    append("If the request is ordinary conversation or explanation, answer directly. ")
                    append("Never claim a device action succeeded unless the Android tool reports success.")
                    append("\nUser message: ").append(input)
                }

                var activeBrain = ""
                var reply: GeminiReply? = null

                if (gemini.isConfigured()) {
                    gemini.askWithTools(enrichedInput, history).onSuccess {
                        activeBrain = "Gemini"
                        reply = it
                    }
                }

                if (reply == null && groq.isConfigured()) {
                    groq.askWithTools(enrichedInput, history).onSuccess {
                        activeBrain = "Groq"
                        reply = it
                    }
                }

                if (reply == null && openRouter.isConfigured()) {
                    openRouter.askWithTools(enrichedInput, history).onSuccess {
                        activeBrain = "OpenRouter"
                        reply = it
                    }
                }

                if (reply == null) {
                    FridayRuntime.update("BRAIN ERROR", "Gemini, Groq and OpenRouter failed", false)
                    finishFailure(input, callback, myGeneration)
                    return@launch
                }

                FridayRuntime.update("AI THINKING", "$activeBrain is reasoning", true)

                var toolTurns = 0
                while (reply?.toolCall != null && toolTurns < MAX_TOOL_TURNS && isActive && !closed && requestGeneration.get() == myGeneration) {
                    val call = reply?.toolCall ?: break
                    toolTurns++
                    val command = call.args.optString("command").trim()
                    FridayRuntime.update("ANDROID TOOL", command.take(160).ifBlank { "Executing requested phone action" }, true)

                    val toolResult = executeGeminiTool(call.name, call.args)
                    val modelContent = reply?.modelContent ?: error("$activeBrain tool call did not include model content")
                    reply = if (activeBrain == "Groq") {
                        groq.continueWithToolResult(history, enrichedInput, modelContent, call, toolResult).getOrElse {
                            GeminiReply(text = "Boss, action ka result mil gaya, lekin Groq final response generate nahi kar paayi.")
                        }
                    } else if (activeBrain == "OpenRouter") {
                        openRouter.continueWithToolResult(history, enrichedInput, modelContent, call, toolResult).getOrElse {
                            GeminiReply(text = "Boss, action ka result mil gaya, lekin OpenRouter final response generate nahi kar paayi.")
                        }
                    } else {
                        gemini.continueWithToolResult(history, enrichedInput, modelContent, call, toolResult).getOrElse {
                            GeminiReply(text = "Boss, action ka result mil gaya, lekin Gemini final response generate nahi kar paayi.")
                        }
                    }
                }

                if (reply?.toolCall != null) {
                    reply = GeminiReply(text = "Boss, main is request ko ek hi run mein safely complete nahi kar paayi.")
                }

                if (closed || !isActive || requestGeneration.get() != myGeneration) return@launch
                val answer = reply?.text?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Boss, mujhe is request ka usable response nahi mila."
                remember("user", input)
                remember("assistant", answer)
                withContext(Dispatchers.Main.immediate) {
                    if (!closed && requestGeneration.get() == myGeneration) {
                        FridayRuntime.update("RESPONSE READY", "$activeBrain response ready for speech", true)
                        callback(answer, false)
                    }
                }
            } finally {
                if (requestGeneration.get() == myGeneration) activeRequest = null
            }
        }
    }

    private fun executeExternal(action: FridayAction, input: String, callback: (String, Boolean) -> Unit) {
        when (action) {
            is FridayAction.News -> callback(news.headlines(action.query), true)
            is FridayAction.AnalyzeMood -> callback("Aapka detected mood: ${emotion.analyze(action.text)}", true)
            else -> callback("", false)
        }
    }

    private fun executeLocal(result: FridayResponse, input: String, callback: (String, Boolean) -> Unit) {
        remember("user", input)
        val action = result.action
        if (action is FridayAction.News || action is FridayAction.AnalyzeMood) {
            if (result.needsConfirmation) {
                val answer = result.text
                remember("assistant", answer)
                callback(answer, true)
            } else executeExternal(action, input, callback)
            return
        }
        if (action is FridayAction.Weather) {
            FridayRuntime.update("WEATHER", "Fetching Open-Meteo weather data", true)
            brainScope.launch {
                val answer = runCatching {
                    val date = action.dateIso?.let { LocalDate.parse(it) }
                    FridayWeatherService(appContext).getWeather(FridayWeatherService.Request(action.location, date))
                }.getOrElse { "Boss, weather service error aa gaya. Main guess nahi karungi." }
                remember("assistant", answer)
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) {
                        FridayRuntime.update("WEATHER READY", answer.take(180), true)
                        callback(answer, true)
                    }
                }
            }
            return
        }
        val requiresConfirmation = result.needsConfirmation && (action == null || FridaySafetyPolicy.requiresConfirmation(action))
        if (action != null && !requiresConfirmation) {
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
        val answer = "Boss, AI connection fail hui. Main koi action complete hone ka false claim nahi karungi."
        remember("user", input)
        remember("assistant", answer)
        withContext(Dispatchers.Main.immediate) {
            if (!closed && requestGeneration.get() == generation) callback(answer, false)
        }
    }

    private fun isCountryOrLocationRequest(input: String): Boolean {
        val lower = input.trim().lowercase(Locale.ROOT)
        if (lower.contains("where am i") || lower.contains("meri location") || lower.contains("my location")) return true
        return Regex("^(?:tell me about|information about|info about|details about|facts about)\\s+(.+)$", RegexOption.IGNORE_CASE).matches(input.trim()) ||
            Regex("^(.+?)\\s+(?:country|desh)\\s+(?:info|information|details)$", RegexOption.IGNORE_CASE).matches(input.trim())
    }

    private fun handleCountryOrLocation(input: String): String? {
        val lower=input.trim().lowercase(Locale.ROOT)
        if (lower.contains("where am i") || lower.contains("meri location") || lower.contains("my location")) return ipInfo.currentLocation()
        val match=Regex("^(?:tell me about|information about|info about|details about|facts about)\\s+(.+)$",RegexOption.IGNORE_CASE).find(input.trim())
            ?: Regex("^(.+?)\\s+(?:country|desh)\\s+(?:info|information|details)$",RegexOption.IGNORE_CASE).find(input.trim())
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf{it.isNotBlank()}?.let{countries.countryInfo(it)}
    }

    private fun handleIdentity(input: String): String? {
        val value = input.trim()
        val lower = value.lowercase(Locale.ROOT)
        if (lower.matches(Regex("(?:what is|what's|whats) my name\\??")) || lower in setOf("mera naam kya hai", "mera name kya hai", "main kaun hoon", "who am i")) {
            val owner = memory.ownerName()
            return if (owner.equals("Boss", ignoreCase = true)) "Aap Boss hain. Main FRIDAY hoon, aapki personal AI assistant." else "Aap " + owner + " Sir hain. Main FRIDAY hoon, aapki personal AI assistant."
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
        groq.cancel()
        openRouter.cancel()
        brainScope.cancel()
        FridayRuntime.update("IDLE", "FRIDAY brain stopped", true)
    }

    private fun handleRoutineCommand(input: String): String? {
        val raw = input.trim()
        val create = Regex("^(?:create|make|save)\\s+(?:a\\s+)?routine\\s+(.+?)\\s*[:=-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (create != null) {
            val name = create.groupValues[1].trim()
            val commands = create.groupValues[2].split(Regex("\\s*(?:;|then)\\s*"), limit = 12).map { it.trim() }.filter { it.isNotBlank() }
            return if (routines.save(name, commands)) "Routine $name save kar di, Boss." else "Boss, routine save nahi ho paayi."
        }
        val run = Regex("^(?:run|start|execute|chalao|chala do)\\s+(?:routine\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (run != null && raw.lowercase(Locale.ROOT).contains("routine")) {
            val name = run.groupValues[1].trim()
            val commands = routines.get(name)
            if (commands.isEmpty()) return "Boss, $name naam ki koi routine nahi mili."
            var completed = 0
            for (command in commands) {
                val result = local.process(command)
                val action = result.action
                if (result.needsConfirmation && (action == null || FridaySafetyPolicy.requiresConfirmation(action))) {
                    return "Boss, routine $name step ${completed + 1} par confirmation required hai: ${result.text}"
                }
                if (action == null) continue
                if (action is FridayAction.Weather) return "Boss, routine $name weather step par ruk gayi; weather ko routine ke andar abhi execute nahi karungi."
                if (!runCatching { launcher.launch(action) }.getOrDefault(false)) {
                    return "Boss, routine $name step ${completed + 1} complete nahi ho paaya."
                }
                completed++
            }
            return "Done Boss. Routine $name ke $completed steps complete ho gaye."
        }
        if (raw.lowercase(Locale.ROOT) in setOf("list routines", "show routines", "meri routines", "routines dikhao")) {
            val names = routines.names()
            return if (names.isEmpty()) "Boss, abhi koi routine saved nahi hai." else "Saved routines: " + names.joinToString(", ")
        }
        val delete = Regex("^(?:delete|remove)\\s+routine\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw)
        if (delete != null) {
            routines.delete(delete.groupValues[1])
            return "Boss, routine ${delete.groupValues[1].trim()} delete kar di."
        }
        return null
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
