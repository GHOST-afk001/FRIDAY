package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GroqProvider(context: Context) {
    private val keys = SecureApiKeyStore(context)
    private val model = "openai/gpt-oss-120b"
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun configureApiKey(key: String) = keys.saveNamed("groq_key", key)
    fun clearApiKey() = keys.saveNamed("groq_key", "")
    fun isConfigured() = !keys.readNamed("groq_key").isNullOrBlank()
    fun cancel() { activeConnection?.disconnect() }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> =
        request(buildMessages(history, prompt))

    fun continueWithToolResult(history: List<Pair<String, String>>, prompt: String, modelMessage: JSONObject, call: GeminiToolCall, result: JSONObject): Result<GeminiReply> {
        val messages = buildMessages(history, prompt)
        messages.put(modelMessage)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", result.toString()))
        return request(messages)
    }

    private fun buildMessages(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        history.takeLast(16).forEach { (role, text) ->
            messages.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        return messages
    }

    private fun request(messages: JSONArray): Result<GeminiReply> = runCatching {
        val key = keys.readNamed("groq_key")?.takeIf { it.isNotBlank() } ?: error("Groq API key is not configured.")
        val tool = JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "android_command")
                .put("description", "Execute a supported Android command on the user's phone.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("command", JSONObject().put("type", "string").put("description", "Natural-language Android action to execute")))
                    .put("required", JSONArray().put("command"))))
        val body = JSONObject()
            .put("model", model).put("messages", messages).put("tools", JSONArray().put(tool))
            .put("tool_choice", "auto").put("temperature", 0.25).put("max_tokens", 1200)
        val connection = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 30000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Cache-Control", "no-store")
        }
        activeConnection = connection
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Groq HTTP $code")
            parseReply(JSONObject(response))
        } finally {
            if (activeConnection === connection) activeConnection = null
            connection.disconnect()
        }
    }

    private fun parseReply(root: JSONObject): GeminiReply {
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: error("Groq returned no message")
        val text = message.optString("content").trim().takeIf { it.isNotBlank() }
        val calls = message.optJSONArray("tool_calls")
        val first = calls?.optJSONObject(0)
        val function = first?.optJSONObject("function")
        val call = if (first != null && function != null) GeminiToolCall(
            first.optString("id", "groq_android_command"),
            function.optString("name"),
            runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
        ) else null
        val modelMessage = JSONObject().put("role", "assistant").put("content", if (text.isNullOrBlank()) JSONObject.NULL else text)
        if (calls != null && calls.length() > 0) modelMessage.put("tool_calls", calls)
        return GeminiReply(text, call, modelMessage)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, the secondary AI reasoning brain for the user's Android assistant.
Understand Hindi, Hinglish and English naturally. Address the user as Boss.
For phone actions, use android_command. Never claim an action succeeded unless the Android executor reports success.
For normal questions, answer naturally and concisely for voice. Do not pretend to have live web access.
"""
    }
}
