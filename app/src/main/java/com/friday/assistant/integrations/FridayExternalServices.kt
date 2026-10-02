package com.friday.assistant.integrations

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private object FridayHttp {
    fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8000; readTimeout = 12000
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) error("HTTP $code")
        return body
    }
}

class FridayLocalMoodService {
    private val positive = setOf("happy","good","great","awesome","excited","love","lovely","khush","mast","accha","acha","pyaar")
    private val negative = setOf("sad","upset","bad","angry","hate","hurt","lonely","depressed","dukhi","udaas","pareshan","gussa","naraz","akela")
    private val anxious = setOf("anxious","anxiety","worried","stress","stressed","nervous","tension","dar","darr","ghabra","ghabrahat")
    private val tired = setOf("tired","exhausted","sleepy","thak","thaka","thaki","neend")
    fun analyze(text: String): String {
        val words = text.lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097f]+"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        fun hits(set: Set<String>) = words.count { word -> set.any { word == it || word.contains(it) } }
        val scores = listOf("positive" to hits(positive), "negative" to hits(negative), "anxious/stressed" to hits(anxious), "tired" to hits(tired))
        val top = scores.maxByOrNull { it.second } ?: return "neutral"
        return if (top.second == 0) "neutral" else top.first + " (" + top.second + " signal" + if (top.second == 1) "" else "s" + ")"
    }
}

class FridayRssNewsService {
    fun headlines(query: String? = null, country: String = "IN"): String {
        val url = if (query.isNullOrBlank()) "https://news.google.com/rss?hl=en-IN&gl=$country&ceid=$country:en"
        else "https://news.google.com/rss/search?q=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20") + "&hl=en-IN&gl=$country&ceid=$country:en"
        val xml = FridayHttp.get(url)
        val items = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE).findAll(xml).take(5).mapNotNull { match ->
            Regex("<title><!\\[CDATA\\[(.*?)]]></title>|<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(match.groupValues[1])?.let {
                (it.groups[1]?.value ?: it.groups[2]?.value.orEmpty()).replace("&amp;", "&").replace(Regex("<[^>]+>"), "").trim()
            }
        }.filter { it.isNotBlank() }.toList()
        return if (items.isEmpty()) "No news found right now." else items.mapIndexed { i, title -> (i + 1).toString() + ". " + title }.joinToString("\\n")
    }
}

class FridayPollinationsService(private val store: com.friday.assistant.ai.SecureApiKeyStore) {
    fun imageUrl(prompt: String): String {
        val encoded = URLEncoder.encode(prompt.trim(), "UTF-8").replace("+", "%20")
        val token = store.readNamed("pollinations").orEmpty()
        val suffix = if (token.isBlank()) "?model=flux" else "?model=flux&key=" + URLEncoder.encode(token, "UTF-8")
        return "https://gen.pollinations.ai/image/" + encoded + suffix
    }
}
