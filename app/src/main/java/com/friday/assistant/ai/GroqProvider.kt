package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Groq fallback brain using the OpenAI-compatible Chat Completions API and local Android tool calling. */
class GroqProvider(context: Context) {
    private val keyStore = SecureApiKeyStore(context, "groq")
    private val model = "openai/gpt-oss-120b"
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var messages = JSONArray()

    fun isConfigured(): Boolean = runCatching { !keyStore.read().isNullOrBlank() }.getOrDefault(false)
    fun setApiKey(key: String): Boolean = keyStore.save(key.trim())
    fun clearApiKey() = keyStore.clear()
    fun cancel() { activeConnection?.disconnect() }

    fun testConnection(): Result<String> = runCatching {
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() } ?: error("Groq API key is not configured.")
        val requestMessages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Reply with exactly: OK"))
            .put(JSONObject().put("role", "user").put("content", "Connection test."))
        extractText(post(apiKey, baseBody(requestMessages, false))).ifBlank { "OK" }
    }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> = runCatching {
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() } ?: error("Groq API key is not configured.")
        messages = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        history.takeLast(8).forEach { (role, text) ->
            messages.put(JSONObject()
                .put("role", if (role.equals("assistant", true)) "assistant" else "user")
                .put("content", text.take(1200)))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        request(apiKey)
    }

    fun continueWithToolResult(call: GeminiToolCall, result: JSONObject): Result<GeminiReply> = runCatching {
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() } ?: error("Groq API key is not configured.")
        messages.put(JSONObject()
            .put("role", "tool")
            .put("tool_call_id", call.id)
            .put("name", call.name)
            .put("content", result.toString()))
        request(apiKey)
    }

    private fun request(apiKey: String): GeminiReply =
        parse(post(apiKey, baseBody(messages, true)))

    private fun baseBody(inputMessages: JSONArray, includeTools: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", inputMessages)
            .put("temperature", 0.2)
            .put("max_completion_tokens", 1200)
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)
        if (includeTools) body.put("tools", JSONArray().put(androidFunction()))
        return body
    }

    private fun androidFunction(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "android_command")
                .put("description", "Control the user's Android phone through FRIDAY's local executor and Accessibility Service. Use this for opening apps, searching inside apps, typing, clicking, scrolling, Home/Back/Recents, flashlight, calls and WhatsApp messages. For multi-step requests, issue one concrete action at a time.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("command",
                        JSONObject().put("type", "string").put("description", "A concrete natural-language Android action to execute now.")))
                    .put("required", JSONArray().put("command"))))

    private fun parse(root: JSONObject): GeminiReply {
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: error("Groq response did not contain a message.")
        messages.put(message)
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val rawCall = toolCalls.optJSONObject(0) ?: error("Groq returned an invalid tool call.")
            val function = rawCall.optJSONObject("function") ?: error("Groq tool call function was missing.")
            val args = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            return GeminiReply(
                text = message.optString("content").trim().takeIf { it.isNotBlank() },
                toolCall = GeminiToolCall(
                    id = rawCall.optString("id").ifBlank { "groq_android_command_0" },
                    name = function.optString("name"),
                    args = args
                ),
                modelContent = JSONObject().put("provider", "groq")
            )
        }
        return GeminiReply(
            text = message.optString("content").trim().takeIf { it.isNotBlank() },
            modelContent = JSONObject().put("provider", "groq")
        )
    }

    private fun extractText(root: JSONObject): String =
        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Cache-Control", "no-store")
        }
        activeConnection = connection
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(formatHttpError(code, response))
            JSONObject(response)
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun formatHttpError(code: Int, response: String): String {
        val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return if (detail.isNotBlank()) "Groq HTTP $code: $detail" else "Groq HTTP $code"
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, Boss's personal Android AI assistant and fallback reasoning brain.
Primary language: natural Indian Hinglish. Secondary language: English.
You are the reasoning brain; the Android executor is your hands.
Always use android_command for phone actions. Never merely explain how to do them.
For multi-step requests, issue one concrete action at a time and use the returned result to decide the next action.
Do not claim success unless the Android executor reports success.
Do not invent current prices, weather, news or other changing facts.
Use the persistent memory context supplied by the app. Do not invent memories.
Keep spoken answers concise, natural and useful. Address the user as Boss.
Never bypass Android permissions, authentication or security boundaries.
"""
    }
}
