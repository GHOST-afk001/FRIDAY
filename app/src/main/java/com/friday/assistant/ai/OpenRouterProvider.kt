package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible third brain with the same Android tool contract as Gemini/Groq. */
class OpenRouterProvider(context: Context) {
    private val keys = SecureApiKeyStore(context)
    private val model = "openai/gpt-5.6-sol"
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun configureApiKey(key: String) = keys.saveNamed("openrouter_key", key.trim())
    fun clearApiKey() = keys.saveNamed("openrouter_key", "")
    fun isConfigured() = !keys.readNamed("openrouter_key").isNullOrBlank()
    fun cancel() { activeConnection?.disconnect() }

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> = runCatching {
        askWithTools(prompt, history).getOrThrow().text
            ?: error("OpenRouter requested an Android tool but returned no final text")
    }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> =
        request(buildMessages(history, prompt))

    fun continueWithToolResult(
        history: List<Pair<String, String>>,
        prompt: String,
        modelContent: JSONObject,
        call: GeminiToolCall,
        result: JSONObject
    ): Result<GeminiReply> {
        val messages = buildMessages(history, prompt)
        messages.put(modelContent)
        messages.put(
            JSONObject()
                .put("role", "tool")
                .put("tool_call_id", call.id)
                .put("content", result.toString())
        )
        return request(messages)
    }

    private fun buildMessages(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "You are FRIDAY, a personal Android AI assistant. Understand Hindi, Hinglish and English. Be concise for voice. Use the android_command tool for phone actions. Never claim an action succeeded unless the Android executor reports success. Never bypass Android permissions or security boundaries."
                    )
            )
        history.takeLast(16).forEach { (role, text) ->
            messages.put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        return messages
    }

    private fun request(messages: JSONArray): Result<GeminiReply> {
        val apiKey = keys.readNamed("openrouter_key") ?: return Result.failure(IllegalStateException("OpenRouter API key is not configured."))
        return runCatching {
            val declaration = JSONObject()
                .put("name", "android_command")
                .put("description", "Execute a supported Android command on the user's phone.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "command",
                                JSONObject().put("type", "string").put("description", "Natural-language Android action to execute")
                            )
                        )
                        .put("required", JSONArray().put("command"))
                )
            val tools = JSONArray().put(
                JSONObject()
                    .put("type", "function")
                    .put("function", declaration)
            )
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("temperature", 0.35)
                .put("max_tokens", 1200)

            val connection = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("X-Title", "FRIDAY Android Assistant")
            }
            activeConnection = connection
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("OpenRouter HTTP $code")
                parseReply(JSONObject(response))
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }
    }

    private fun parseReply(root: JSONObject): GeminiReply {
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: error("OpenRouter returned no message")
        val text = message.optString("content").trim().takeIf { it.isNotBlank() }
        val toolCalls = message.optJSONArray("tool_calls")
        val first = toolCalls?.optJSONObject(0)
        val call = first?.let {
            val fn = it.optJSONObject("function") ?: JSONObject()
            val rawArgs = fn.optString("arguments").trim()
            val args = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
            GeminiToolCall(
                id = it.optString("id", "android_command_0"),
                name = fn.optString("name"),
                args = args
            )
        }
        return GeminiReply(text = text, toolCall = call, modelContent = message)
    }
}
