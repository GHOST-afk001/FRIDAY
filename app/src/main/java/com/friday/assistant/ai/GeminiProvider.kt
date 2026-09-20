package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiToolCall(val id: String, val name: String, val args: JSONObject)
data class GeminiReply(val text: String? = null, val toolCall: GeminiToolCall? = null, val modelContent: JSONObject? = null)

class GeminiProvider(context: Context) {
    private val keyStore = SecureApiKeyStore(context)
    private val model = "gemini-3.8-flash"
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun isConfigured(): Boolean = runCatching { !keyStore.read().isNullOrBlank() }.getOrDefault(false)
    fun setApiKey(key: String): Boolean = keyStore.save(key.trim())
    fun clearApiKey() = keyStore.clear()
    fun cancel() { activeConnection?.disconnect() }

    /** Performs a minimal Gemini request without tools so key/model/network failures are isolated. */
    fun testConnection(): Result<String> {
        val apiKey = runCatching { keyStore.read() }.getOrNull()
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val body = JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "Reply with exactly: OK")))
                ))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
            val connection = openConnection(apiKey)
            activeConnection = connection
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException(formatHttpError(code, response))
                parseReply(JSONObject(response)).text?.ifBlank { "OK" } ?: "OK"
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }
    }

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> = runCatching {
        val reply = askWithTools(prompt, history).getOrThrow()
        reply.text ?: error("Gemini requested an Android tool but no executor was attached")
    }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> =
        request(buildConversation(history, prompt))

    fun continueWithToolResult(history: List<Pair<String, String>>, prompt: String, modelContent: JSONObject, call: GeminiToolCall, result: JSONObject): Result<GeminiReply> {
        val contents = buildConversation(history, prompt)
        contents.put(modelContent)
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("functionResponse", JSONObject().put("name", call.name).put("id", call.id).put("response", result)))))
        return request(contents)
    }

    private fun buildConversation(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val contents = JSONArray()
        history.takeLast(16).forEach { (role, text) ->
            contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        return contents
    }

    private fun request(contents: JSONArray): Result<GeminiReply> {
        val apiKey = runCatching { keyStore.read() }.getOrNull()
            ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val declaration = JSONObject()
                .put("name", "android_command")
                .put("description", "Control the user's Android phone through the local executor and Accessibility Service. Use this for every device action. You can open apps, perform web/app searches, click visible controls, type text, scroll, go Home/Back/Recents, toggle supported device controls, and send WhatsApp messages. For multi-step requests, issue one concrete action at a time and continue until the requested task is complete.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject().put("command", JSONObject().put("type", "STRING").put("description", "Natural-language Android action to execute")))
                    .put("required", JSONArray().put("command")))
            val tools = JSONArray()
                .put(JSONObject().put("googleSearch", JSONObject()))
                .put(JSONObject().put("functionDeclarations", JSONArray().put(declaration)))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents).put("tools", tools)
                .put("toolConfig", JSONObject().put("includeServerSideToolInvocations", true))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1200))
            val connection = openConnection(apiKey)
            activeConnection = connection
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error(formatHttpError(code, response))
                parseReply(JSONObject(response))
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }
    }

    private fun openConnection(apiKey: String): HttpURLConnection =
        (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
            setRequestProperty("Cache-Control", "no-store")
        }

    private fun formatHttpError(code: Int, response: String): String {
        val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return if (detail.isNotBlank()) "Gemini HTTP $code: $detail" else "Gemini HTTP $code"
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
            part.optJSONObject("functionCall")?.let { fc ->
                call = GeminiToolCall(fc.optString("id", "android_command_$i"), fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
            }
        }
        return GeminiReply(text?.trim(), call, content)
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, Imroz Sir's personal Android AI assistant. Address him as Imroz Sir or Boss. Understand Hindi and Hinglish as the PRIMARY language and English as the SECONDARY language. Prefer natural Indian Hinglish in spoken responses unless the user clearly speaks only English.
You are the reasoning brain; Android's local executor plus the enabled Accessibility Service are your hands. For phone actions, ALWAYS use android_command instead of merely explaining what to do. You may open apps, search the web or inside apps, tap visible controls, type into editable fields, scroll, navigate Home/Back/Recents, toggle supported device controls, and send WhatsApp messages when the required user permissions are enabled. For multi-step requests, plan the steps and execute them in order. If the first action only opens an app, immediately perform the next requested action; do not stop early. Examples: "YouTube kholo aur Arijit Singh search karo" => open YouTube then search; "WhatsApp kholo aur Baaji ko bolo hello" => open the Baaji chat and send hello; "Chrome kholo aur BMW M3 price search karo" => open Chrome and perform the search; "flashlight on karo" => execute the flashlight action. Never claim an action succeeded unless the executor confirms success. Do not stop at merely opening an app when the user asked you to complete the task. Verify each executor result before moving to the next step. Respect confirmation requirements for calls or other protected actions. Never bypass Android permissions, authentication, security or privacy boundaries.
For normal questions, answer naturally and concisely. Keep spoken responses short and clear for TTS.
For current weather, current events, recent news, prices, sports, or any worldwide/current information, use the Google Search tool and base the answer on the retrieved web results. For a request to search the user's phone/browser/app, use android_command to operate the device; Google Search is for answering current information, not for pretending to control the screen. Do not claim you searched if the tool did not return results.
For emotional or casual conversation, respond naturally and empathetically; never stay silent just because the request is not an Android action.
"""
    }
}
