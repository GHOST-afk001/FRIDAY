package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiToolCall(
    val id: String,
    val name: String,
    val args: JSONObject
)

data class GeminiReply(
    val text: String? = null,
    val toolCall: GeminiToolCall? = null,
    val modelContent: JSONObject? = null
)

/** REST Gemini bridge with native function calling for Android actions. */
class GeminiProvider(context: Context) {
    private val keyStore = SecureApiKeyStore(context)
    private val model = "gemini-3.8-flash"
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun isConfigured(): Boolean = runCatching { !keyStore.read().isNullOrBlank() }.getOrDefault(false)
    fun setApiKey(key: String) = keyStore.save(key.trim())
    fun clearApiKey() = keyStore.clear()
    fun cancel() { activeConnection?.disconnect() }

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> = runCatching {
        val reply = askWithTools(prompt, history).getOrThrow()
        reply.text ?: error("Gemini requested an Android tool but no executor was attached")
    }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> =
        request(buildConversation(history, prompt))

    fun continueWithToolResult(
        history: List<Pair<String, String>>,
        prompt: String,
        modelContent: JSONObject,
        call: GeminiToolCall,
        result: JSONObject
    ): Result<GeminiReply> {
        val contents = buildConversation(history, prompt)
        contents.put(modelContent)
        contents.put(
            JSONObject().put("role", "user").put(
                "parts", JSONArray().put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", call.name).put("id", call.id).put("response", result)
                    )
                )
            )
        )
        return request(contents)
    }

    private fun buildConversation(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val contents = JSONArray()
        history.takeLast(16).forEach { (role, text) ->
            contents.put(
                JSONObject().put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        return contents
    }

    private fun request(contents: JSONArray): Result<GeminiReply> {
        val apiKey = runCatching { keyStore.read() }.getOrNull()
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val androidCommandDeclaration = JSONObject()
                .put("name", "android_command")
                .put(
                    "description",
                    "Execute a concrete action on the owner's Android phone. Use it for app launches, app searches, YouTube/Spotify searches, web search, maps/navigation, calculator, camera, settings, flashlight, volume, timers, alarms, calls, SMS, WhatsApp, Instagram, Messages, and cross-app Accessibility actions such as click text, click a description, type text, scroll, home, back, recents, or notifications. The app executes the command and returns the real result. Never invent success. Put the complete natural-language command in command."
                )
                .put(
                    "parameters",
                    JSONObject().put("type", "object").put(
                        "properties",
                        JSONObject().put(
                            "command",
                            JSONObject().put("type", "string").put(
                                "description",
                                "Complete concise Android command, e.g. 'open Spotify', 'search YouTube for lo-fi music', 'turn flashlight on', 'click Send', 'type hello', 'scroll down', or 'go back'."
                            )
                        )
                    ).put("required", JSONArray().put("command"))
                )

            val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(androidCommandDeclaration)))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
                .put("tools", tools)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1200))

            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Cache-Control", "no-store")
            }
            activeConnection = connection
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Gemini HTTP $code")
                parseResponse(JSONObject(response))
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }
    }

    private fun parseResponse(root: JSONObject): GeminiReply {
        val candidates = root.optJSONArray("candidates") ?: error("Gemini returned no candidates")
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: error("Gemini returned no content")
        val parts = content.optJSONArray("parts") ?: error("Gemini returned no parts")
        val text = buildString {
            for (i in 0 until parts.length()) {
                val value = parts.optJSONObject(i)?.optString("text").orEmpty().trim()
                if (value.isNotBlank()) { if (isNotEmpty()) append('\n'); append(value) }
            }
        }.trim()

        for (i in 0 until parts.length()) {
            val functionCall = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            val name = functionCall.optString("name").trim()
            if (name.isBlank()) continue
            val id = functionCall.optString("id", "friday-${System.currentTimeMillis()}")
            val args = functionCall.optJSONObject("args") ?: JSONObject()
            return GeminiReply(toolCall = GeminiToolCall(id, name, args), modelContent = content)
        }
        if (text.isBlank()) error("Gemini returned an empty response")
        return GeminiReply(text = text, modelContent = content)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, an intelligent JARVIS-like Android assistant for Imroz Sir (Boss).
Understand Hindi, Hinglish and English naturally. Be concise when speaking, but reason carefully before acting.

PHONE CONTROL:
- If the owner asks you to do something on the phone, use android_command instead of merely explaining it.
- Treat the Android tool result as the only source of truth for whether an action succeeded.
- For app control, you may first open the requested app and then use Accessibility commands such as click, click by description, type, scroll, back, home, recents, or notifications when supported.
- For multi-step requests, perform the steps sequentially and use a new tool call after each verified result.
- Never claim that an action happened unless the tool explicitly reports success.
- If Android reports confirmation_required, ask the owner for confirmation and do not bypass the safety boundary.
- Do not invent unsupported access to private app data, passwords, banking controls, or security-protected screens.

INTELLIGENCE:
- Answer normal questions directly.
- Use the owner's recent conversation history when useful, but do not expose secrets or API keys.
- Match the owner's language and keep spoken responses natural.
"""
    }
}
