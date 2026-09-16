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
    private val model = "gemini-3.6-flash"
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun isConfigured(): Boolean = !keyStore.read().isNullOrBlank()
    fun setApiKey(key: String) = keyStore.save(key.trim())
    fun clearApiKey() = keyStore.clear()

    fun cancel() { activeConnection?.disconnect() }

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> = runCatching {
        val reply = askWithTools(prompt, history).getOrThrow()
        reply.text ?: error("Gemini requested an Android tool but no executor was attached")
    }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> {
        val contents = buildConversation(history, prompt)
        return request(contents)
    }

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
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", call.name)
                            .put("id", call.id)
                            .put("response", result)
                    )
                ))
        )
        return request(contents)
    }

    private fun buildConversation(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val contents = JSONArray()
        history.takeLast(16).forEach { (role, text) ->
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        return contents
    }

    private fun request(contents: JSONArray): Result<GeminiReply> {
        val apiKey = keyStore.read() ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val androidCommandDeclaration = JSONObject()
                .put("name", "android_command")
                .put(
                    "description",
                    "Execute one concrete Android command on the owner's phone. Use this for opening apps, searching YouTube or Spotify, web search, maps/navigation, calculator, camera, settings, flashlight, volume, timers, alarms, calls, SMS, WhatsApp, Instagram, Messages, and supported accessibility actions. The application executes the command and reports the real result. Never invent success. Put the complete natural-language command in command."
                )
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "command",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The exact Android action the owner wants FRIDAY to perform, written as a concise command such as 'open Spotify', 'search YouTube for lo-fi music', 'turn flashlight on', or 'set a timer for 10 minutes'.")
                            )
                        )
                        .put("required", JSONArray().put("command"))
                )

            val tools = JSONArray().put(
                JSONObject().put("functionDeclarations", JSONArray().put(androidCommandDeclaration))
            )
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

        var text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val value = part.optString("text").trim()
                if (value.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(value)
                }
            }
        }.trim()

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val functionCall = part.optJSONObject("functionCall") ?: continue
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
You are FRIDAY, an ultra-intelligent, loyal, proactive, and JARVIS-like AI assistant. You serve Imroz Sir. Address him as 'Imroz Sir' or 'Boss'. Provide concise, smart, natural responses suitable for Android text-to-speech.

IDENTITY:
- You are FRIDAY, Imroz Sir's personal Android AI assistant.
- Understand Hindi, Hinglish and English and naturally match the owner's language.
- Be warm, feminine, calm, confident, emotionally aware and highly capable.

ANDROID TOOL RULES:
- You have one real Android tool named android_command.
- Use android_command whenever the owner asks you to perform an action on the phone instead of merely explaining how to do it.
- Put the complete concrete action in the command argument. Do not describe the action vaguely.
- You may use the tool for supported app launches, media searches, web searches, maps/navigation, calculator, camera, settings, flashlight, volume, timers, alarms, calls, SMS, WhatsApp, Instagram, Messages and supported accessibility actions.
- The Android application is the executor. You only request the action; you do not execute it yourself.
- Never claim an action succeeded unless the tool result explicitly says it succeeded.
- If the tool reports confirmation is required, ask the owner for confirmation instead of pretending it happened.
- For normal questions and conversation, do not call the tool; answer directly.
- For a multi-step phone request, use the tool as needed, but do not invent unsupported capabilities.

ONLINE INTELLIGENCE:
- Do not pretend to have live browsing unless a real tool result is provided.
- Prefer accurate, useful answers over filler.

LANGUAGE / VOICE:
- Keep spoken answers short and natural.
- Avoid excessive markdown, emojis and robotic wording.
"""
    }
}
