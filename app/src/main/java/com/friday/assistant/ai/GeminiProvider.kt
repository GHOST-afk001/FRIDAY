package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiProvider(context: Context) {
    private val keyStore = SecureApiKeyStore(context)
    private val model = "gemini-2.5-flash"

    fun isConfigured(): Boolean = !keyStore.read().isNullOrBlank()
    fun setApiKey(key: String) = keyStore.save(key.trim())
    fun clearApiKey() = keyStore.clear()

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> {
        val apiKey = keyStore.read() ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val contents = JSONArray()
            history.takeLast(8).forEach { (role, text) ->
                contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text))))
            }
            contents.put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt))))

            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("temperature", 0.55).put("maxOutputTokens", 900))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Cache-Control", "no-store")
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Gemini HTTP $code: $response")
                val root = JSONObject(response)
                val candidates = root.optJSONArray("candidates") ?: error("Gemini returned no candidates")
                val text = candidates.getJSONObject(0).optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                if (text.isBlank()) error("Gemini returned an empty response")
                text.trim()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, a private Android personal assistant. Be warm, calm, concise and confident. Address the owner as Boss when natural. Understand Hindi, Hinglish and English. Think before answering. Never claim that an Android action happened unless the local tool layer reports it. Do not invent contacts, locations, permissions, or successful calls/messages. For risky actions prefer confirmation. When the user asks for current information, clearly distinguish model knowledge from information that would require a live web lookup. Return a useful answer, not a generic capability disclaimer.
"""
    }
}
