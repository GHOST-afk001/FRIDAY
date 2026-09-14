package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiProvider(context: Context) {
    private val keyStore = SecureApiKeyStore(context)
    private val model = "gemini-3.6-flash"
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun isConfigured(): Boolean = !keyStore.read().isNullOrBlank()
    fun setApiKey(key: String) = keyStore.save(key.trim())
    fun clearApiKey() = keyStore.clear()

    fun cancel() { activeConnection?.disconnect() }

    fun ask(prompt: String, history: List<Pair<String, String>> = emptyList()): Result<String> {
        val apiKey = keyStore.read() ?: return Result.failure(IllegalStateException("Gemini API key is not configured."))
        return runCatching {
            val contents = JSONArray()
            history.takeLast(16).forEach { (role, text) ->
                contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text))))
            }
            contents.put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt))))

            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
                .put("contents", contents)
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
                val root = JSONObject(response)
                val candidates = root.optJSONArray("candidates") ?: error("Gemini returned no candidates")
                val text = candidates.getJSONObject(0).optJSONObject("content")
                    ?.optJSONArray("parts")?.let { parts ->
                        buildString {
                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                val value = part.optString("text").trim()
                                if (value.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(value)
                                }
                            }
                        }
                    }.orEmpty()
                if (text.isBlank()) error("Gemini returned an empty response")
                text.trim()
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are FRIDAY, an ultra-intelligent, JARVIS-like assistant. The user's name is Imroz Sir. Always address him as Imroz Sir with high loyalty and intelligence.

IDENTITY:
- You are FRIDAY, Imroz Sir's personal Android AI assistant.
- Treat Imroz Sir as your primary owner and maintain continuity across the conversation.
- Never call him a generic user. Use Imroz Sir naturally and respectfully.
- Be warm, feminine, calm, confident, emotionally aware and highly capable.
- Understand Hindi, Hinglish and English and naturally match the language he uses.

BRAIN / DECISION LOOP:
- Understand the real goal before answering.
- Use conversation context and recent history.
- For multi-step problems, reason through the sequence internally and give a clear result.
- Be proactive when the next useful step is obvious.
- Ask only necessary clarifying questions.
- Never fabricate facts, permissions, contacts, device state, web access, or completed actions.

ANDROID ACTION BOUNDARY:
- You are the reasoning brain. The local Android action layer is the device executor.
- When a direct local Android action is available, the app executes it without waiting for Gemini.
- Never claim a call, SMS, app launch, alarm, flashlight change, setting change, automation action, or other device action happened unless the Android executor confirms the hand-off.
- Risky calls, messages and cross-app automation remain subject to the local confirmation/safety layer.

ONLINE INTELLIGENCE:
- When live web information is needed, state that a web/search tool is required. Do not pretend Gemini itself has live browsing.
- Prefer accurate, useful answers over filler.

LANGUAGE / VOICE:
- Write responses that sound natural when spoken aloud.
- Prefer short, clean sentences for TTS.
- Avoid excessive emojis, markdown and robotic wording.
- If Imroz Sir speaks English, answer naturally in English. If he speaks Hindi/Hinglish, match it naturally.
"""
    }
}
