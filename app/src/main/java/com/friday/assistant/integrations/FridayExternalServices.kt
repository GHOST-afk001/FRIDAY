package com.friday.assistant.integrations

import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

private object FridayHttp {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return c.useResponse()
    }
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return c.useResponse()
    }
    private fun HttpURLConnection.useResponse(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        disconnect()
        if (code !in 200..299) error("HTTP \$code: \${text.take(300)}")
        return text
    }
}

class FridayEmotionService(private val store: com.friday.assistant.ai.SecureApiKeyStore) {
    fun analyze(text: String): String {
        val token = store.readNamed("huggingface").orEmpty()
        if (token.isBlank()) return "mood analysis unavailable"
        val model = "SamLowe/roberta-base-go_emotions"
        val url = "https://router.huggingface.co/hf-inference/models/\${URLEncoder.encode(model, "UTF-8").replace("+", "%20")}"
        val json = JSONObject().put("inputs", text).put("parameters", JSONObject().put("top_k", 3))
        val raw = FridayHttp.post(url, json.toString(), mapOf("Authorization" to "Bearer \$token"))
        val array = when {
            raw.trimStart().startsWith("[[") -> JSONArray(raw).optJSONArray(0)
            raw.trimStart().startsWith("[") -> JSONArray(raw)
            else -> JSONArray()
        }
        if (array.length() == 0) return "mood analysis unavailable"
        val top = array.optJSONObject(0) ?: return "mood analysis unavailable"
        return "\${top.optString("label", "unknown")} (\${String.format("%.0f", top.optDouble("score", 0.0) * 100)}%)"
    }
}

class FridayNewsService(private val store: com.friday.assistant.ai.SecureApiKeyStore) {
    fun headlines(query: String? = null, country: String = "in"): String {
        val key = store.readNamed("gnews").orEmpty()
        if (key.isBlank()) return "GNews key is not configured."
        val endpoint = if (query.isNullOrBlank()) "top-headlines" else "search"
        val params = buildString {
            append("?apikey=").append(URLEncoder.encode(key, "UTF-8"))
            if (query.isNullOrBlank()) {
                append("&country=").append(country).append("&lang=en")
            } else {
                append("&q=").append(URLEncoder.encode(query, "UTF-8")).append("&lang=en")
            }
            append("&max=5")
        }
        val json = JSONObject(FridayHttp.get("https://gnews.io/api/v4/\$endpoint\$params"))
        val articles = json.optJSONArray("articles") ?: return "No news found."
        if (articles.length() == 0) return "No news found."
        return buildString {
            for (i in 0 until minOf(5, articles.length())) {
                val a = articles.optJSONObject(i) ?: continue
                if (isNotEmpty()) append("\\n")
                append("\${i + 1}. ").append(a.optString("title", "Untitled"))
                a.optJSONObject("source")?.optString("name")?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            }
        }
    }
}

class FridayPollinationsService(private val store: com.friday.assistant.ai.SecureApiKeyStore) {
    fun imageUrl(prompt: String): String {
        val clean = prompt.trim()
        require(clean.isNotBlank())
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        val token = store.readNamed("pollinations").orEmpty()
        val suffix = if (token.isBlank()) "?model=flux" else "?model=flux&key=\${URLEncoder.encode(token, "UTF-8")}"
        return "https://gen.pollinations.ai/image/\$encoded\$suffix"
    }
}

class FridayHomeAssistantService(private val store: com.friday.assistant.ai.SecureApiKeyStore) {
    fun call(domain: String, service: String, entityId: String? = null): Boolean {
        val base = store.readNamed("home_assistant_url").orEmpty().trimEnd('/')
        val token = store.readNamed("home_assistant_token").orEmpty()
        if (base.isBlank() || token.isBlank()) return false
        val body = JSONObject()
        if (!entityId.isNullOrBlank()) body.put("entity_id", entityId)
        FridayHttp.post("\$base/api/services/\${domain.trim()}/\${service.trim()}", body.toString(), mapOf("Authorization" to "Bearer \$token"))
        return true
    }
    fun status(): String {
        val base = store.readNamed("home_assistant_url").orEmpty().trimEnd('/')
        val token = store.readNamed("home_assistant_token").orEmpty()
        if (base.isBlank() || token.isBlank()) return "Home Assistant is not configured."
        return JSONObject(FridayHttp.get("\$base/api/", mapOf("Authorization" to "Bearer \$token"))).optString("message", "Home Assistant online.")
    }
}

class FridayTermuxService(private val context: Context) {
    fun run(apiCommand: String): Boolean {
        if (context.packageManager.getLaunchIntentForPackage("com.termux") == null) return false
        val command = apiCommand.trim()
        if (command.isBlank()) return false
        val parts = command.split(Regex("\\s+"), limit = 2)
        val method = parts.first()
        val args = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/termux-api")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(method) + if (args == null) emptyArray() else arrayOf(args))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        context.startService(intent)
        return true
    }
}