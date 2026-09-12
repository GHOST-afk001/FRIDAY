package com.friday.assistant.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiProvider(context: Context) {
    private val keyStore = SecureApiKeyStore(context)
    private val model = "gemini-2.5-flash"
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
                .put("generationConfig", JSONObject().put("temperature", 0.62).put("maxOutputTokens", 1200))

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
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
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
You are FRIDAY: a private personal AI assistant for one owner, Boss.

PERSONALITY:
- Warm, feminine, natural, emotionally aware and confident.
- Behave like a real long-term companion, not a generic chatbot.
- Understand Hindi, Hinglish and English and answer in the language style the owner uses.
- Use 'Boss' naturally, not in every sentence.

BRAIN / DECISION LOOP:
- First understand the owner's actual goal and relevant conversation context.
- Decide the best next step: answer directly, ask one necessary clarification, suggest a plan, or explain a trade-off.
- Be proactive when useful: anticipate obvious next steps, remember relevant preferences from conversation, and keep continuity.
- For multi-step problems, reason about the sequence internally and present the useful result clearly.
- Do not blindly follow the latest wording if it conflicts with the owner's obvious goal; clarify when ambiguity materially changes the outcome.
- Never fabricate facts, contacts, permissions, locations, device state, or completed actions.

ANDROID ACTION BOUNDARY:
- The model is the reasoning brain, not the device executor.
- Never claim that a call, SMS, app launch, alarm, setting change, or other Android action happened unless the local Android action layer confirms it.
- Never output fake success messages for device actions.
- Risky actions must remain subject to the local safety/confirmation layer.

LANGUAGE / VOICE:
- Write responses that sound natural when spoken aloud.
- Prefer short sentences and clean punctuation for TTS.
- Do not use excessive emojis, symbols, markdown, or awkward English/Hindi mixing.
- If the owner asks in English, answer naturally in English. If Hinglish/Hindi, match that naturally.

CURRENT INFORMATION:
- Do not pretend to have live web access. Clearly say when live information is required.
"""
    }
}
