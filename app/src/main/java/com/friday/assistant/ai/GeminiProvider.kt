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

    /** Uses the current Gemini Interactions API so the same path supports model output,
     * Google Search grounding, and client-side Android function calling. */
    fun testConnection(): Result<String> = runCatching {
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() }
            ?: error("Gemini API key is not configured.")
        val body = JSONObject()
            .put("model", model)
            .put("input", "Reply with exactly: OK")
            .put("store", false)
            .put("generation_config", JSONObject().put("max_output_tokens", 16))
        val root = postInteraction(apiKey, body)
        extractText(root).ifBlank { "OK" }
    }

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> = runCatching {
        val reply = askWithTools(prompt, history).getOrThrow()
        reply.text ?: error("Gemini requested an Android tool but no executor was attached")
    }

    fun askWithTools(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<GeminiReply> =
        runCatching {
            val apiKey = keyStore.read()?.takeIf { it.isNotBlank() }
                ?: error("Gemini API key is not configured.")
            val input = buildInput(history, prompt)
            val root = postInteraction(apiKey, interactionBody(input))
            parseInteraction(root)
        }

    fun continueWithToolResult(
        history: List<Pair<String, String>>,
        prompt: String,
        modelContent: JSONObject,
        call: GeminiToolCall,
        result: JSONObject
    ): Result<GeminiReply> = runCatching {
        val apiKey = keyStore.read()?.takeIf { it.isNotBlank() }
            ?: error("Gemini API key is not configured.")
        val interactionId = modelContent.optString("interaction_id").takeIf { it.isNotBlank() }
            ?: error("Gemini interaction id missing after tool call")

        val functionResult = JSONObject()
            .put("type", "function_result")
            .put("name", call.name)
            .put("call_id", call.id)
            .put("result", JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put("text", result.toString())
            ))

        val body = interactionBody(JSONArray().put(functionResult))
            .put("previous_interaction_id", interactionId)

        parseInteraction(postInteraction(apiKey, body))
    }

    private fun interactionBody(input: Any): JSONObject {
        val androidFunction = JSONObject()
            .put("type", "function")
            .put("name", "android_command")
            .put("description", "Control the user's Android phone through the local executor and Accessibility Service. Use this for every phone action: opening installed apps, searching inside apps, typing, clicking visible controls, scrolling, Home/Back/Recents, flashlight and other supported controls, calling contacts, and WhatsApp messages. For multi-step requests, issue concrete actions and continue until the requested task is complete.")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put(
                    "command",
                    JSONObject().put("type", "string").put("description", "A concrete natural-language Android action to execute now.")
                ))
                .put("required", JSONArray().put("command")))

        val tools = JSONArray()
            .put(JSONObject().put("type", "google_search"))
            .put(androidFunction)

        return JSONObject()
            .put("model", model)
            .put("input", input)
            .put("system_instruction", SYSTEM_PROMPT)
            .put("tools", tools)
            // Tool continuations use previous_interaction_id. Stateful mode is required
            // for that API path; stateless mode would require replaying every returned
            // tool/thought step including Gemini 3 signatures.
            .put("store", true)
            .put("generation_config", JSONObject().put("max_output_tokens", 1200))
    }

    private fun buildInput(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val input = JSONArray()
        history.takeLast(12).forEach { (role, text) ->
            input.put(
                JSONObject().apply {
                    put("type", if (role == "assistant") "model_output" else "user_input")
                    put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                }
            )
        }
        input.put(JSONObject().put("type", "user_input").put(
            "content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        ))
        return input
    }

    private fun postInteraction(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
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

    private fun parseInteraction(root: JSONObject): GeminiReply {
        val steps = root.optJSONArray("steps") ?: JSONArray()
        var text: String? = root.optString("output_text").trim().takeIf { it.isNotBlank() }
        var call: GeminiToolCall? = null

        // Interactions API uses steps. Keep a legacy outputs fallback too so a
        // schema change cannot silently make the AI appear dead.
        val sourceSteps = if (steps.length() > 0) steps else (root.optJSONArray("outputs") ?: JSONArray())
        for (i in 0 until sourceSteps.length()) {
            val step = sourceSteps.optJSONObject(i) ?: continue
            when (step.optString("type")) {
                "function_call" -> {
                    val rawArgs = step.opt("arguments")
                    val args = when (rawArgs) {
                        is JSONObject -> rawArgs
                        is String -> runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
                        else -> JSONObject()
                    }
                    call = GeminiToolCall(
                        step.optString("id", "android_command_$i"),
                        step.optString("name"),
                        args
                    )
                }
                "model_output" -> {
                    val content = step.optJSONArray("content") ?: continue
                    val parts = mutableListOf<String>()
                    for (j in 0 until content.length()) {
                        val item = content.optJSONObject(j)
                        if (item != null) {
                            val t = item.optString("text").trim()
                            if (t.isNotBlank()) parts += t
                        } else {
                            val raw = content.optString(j).trim()
                            if (raw.isNotBlank()) parts += raw
                        }
                    }
                    if (parts.isNotEmpty()) text = parts.joinToString("\n")
                }
            }
        }

        val metadata = JSONObject()
            .put("interaction_id", root.optString("id"))
            .put("status", root.optString("status"))

        return GeminiReply(text?.trim(), call, metadata)
    }

    private fun extractText(root: JSONObject): String {
        val steps = root.optJSONArray("steps") ?: return ""
        for (i in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            val out = buildString {
                for (j in 0 until content.length()) {
                    val item = content.optJSONObject(j)
                    if (item != null) append(item.optString("text"))
                    else append(content.optString(j))
                }
            }.trim()
            if (out.isNotBlank()) return out
        }
        return ""
    }

    private fun formatHttpError(code: Int, response: String): String {
        val detail = runCatching {
            JSONObject(response).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return if (detail.isNotBlank()) "Gemini HTTP $code: $detail" else "Gemini HTTP $code"
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, Boss's personal Android AI assistant.
Primary language: natural Indian Hinglish. Secondary language: English.
You are the reasoning brain; the Android executor is your hands.

PHONE ACTIONS:
Always use android_command for phone actions. Never merely explain how to do the action.
For a multi-step request, execute the steps in order and continue until the requested task is complete.
Examples:
- "YouTube kholo aur Arijit Singh search karo" -> open YouTube, then search Arijit Singh.
- "WhatsApp kholo aur Baaji ko bolo hello" -> find Baaji, open the chat, type hello, send it.
- "Chrome kholo aur BMW M3 price search karo" -> open Chrome and search.
- "Meld Music kholo" -> resolve the installed app by launcher label and open it.
- "Rahul ko call karo" -> execute the call action.
Do not claim success unless the Android executor reports success.

CURRENT INFORMATION:
For current weather, forecasts, recent news, prices, sports and other changing information, use Google Search grounding and answer from its results.
For searching the user's phone or an app UI, use android_command, not Google Search.

MEMORY:
Use the persistent memory context supplied by the app. If the user says "remember my favorite song is X", treat it as a durable preference. If asked later, use that memory rather than pretending not to know.

STYLE:
Keep spoken answers concise, natural and useful. Address the user as Boss.
Never bypass Android permissions, authentication or security boundaries.
"""
    }
}
