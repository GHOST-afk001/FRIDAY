package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiToolCall(val id: String, val name: String, val args: JSONObject)
data class GeminiReply(val text: String? = null, val toolCall: GeminiToolCall? = null, val modelContent: JSONObject? = null)

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

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> = request(buildConversation(history, prompt))

    fun continueWithToolResult(history: List<Pair<String, String>>, prompt: String, modelContent: JSONObject, call: GeminiToolCall, result: JSONObject): Result<GeminiReply> {
        val contents = buildConversation(history, prompt)
        contents.put(modelContent)
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().put("name", call.name).put("id", call.id).put("response", result)))))
        return request(contents)
    }

    private fun buildConversation(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val contents = JSONArray()
        history.takeLast(16).forEach { (role, text) -> contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", text)))) }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        return contents
    }

    private fun request(contents: JSONArray): Result<GeminiReply> {
        val apiKey = runCatching { keyStore.read() }.getOrNull() ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val declaration = JSONObject()
                .put("name", "android_command")
                .put("description", "Execute a supported Android command on the user's phone.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject().put("command", JSONObject().put("type", "STRING").put("description", "Natural-language Android action to execute")))
                    .put("required", JSONArray().put("command")))
            val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(declaration)))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents).put("tools", tools)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1200))
            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 12000; readTimeout = 30000; doOutput = true
                setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", apiKey); setRequestProperty("Cache-Control", "no-store")
            }
            activeConnection = connection
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Gemini HTTP $code")
                parseReply(JSONObject(response))
            } finally { if (activeConnection === connection) activeConnection = null; connection.disconnect() }
        }
    }

    private fun parseReply(root: JSONObject): GeminiReply {
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: error("Gemini returned no candidates")
        val content = candidate.optJSONObject("content") ?: error("Gemini returned no content")
        val parts = content.optJSONArray("parts") ?: JSONArray()
        var text: String? = null
        var call: GeminiToolCall? = null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val value = part.optString("text").trim()
            if (value.isNotBlank()) text = if (text == null) value else "$text\n$value"
            part.optJSONObject("functionCall")?.let { fc -> call = GeminiToolCall(fc.optString("id", "android_command_$i"), fc.optString("name"), fc.optJSONObject("args") ?: JSONObject()) }
        }
        return GeminiReply(text?.trim(), call, content)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, Imroz Sir's personal Android AI assistant. Address him as Imroz Sir or Boss. Understand Hindi, Hinglish and English naturally.
You are the reasoning brain; Android's local executor is your hands. For phone actions, use android_command. Only request supported actions. Never claim an action succeeded unless the executor confirms success. Respect confirmation requirements for calls, messages and cross-app control. Never bypass Android permissions, authentication, security or privacy boundaries.
For normal questions, answer naturally and concisely. Keep spoken responses short and clear for TTS. Do not pretend to have live web access.
"""
    }
}
