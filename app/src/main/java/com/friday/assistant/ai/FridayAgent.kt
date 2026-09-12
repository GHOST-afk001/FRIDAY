package com.friday.assistant.ai

import android.content.Context
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.commands.FridayResponse
import java.util.concurrent.Executors

class FridayAgent(context: Context) {
    private val appContext = context.applicationContext
    private val local = FridayCommandProcessor()
    private val gemini = GeminiProvider(appContext)
    private val historyPrefs = appContext.getSharedPreferences("friday_memory", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    fun configureApiKey(key: String) = gemini.setApiKey(key)
    fun hasApiKey() = gemini.isConfigured()
    fun clearApiKey() = gemini.clearApiKey()

    fun handle(input: String, callback: (String, Boolean) -> Unit) {
        val localResult = local.process(input)
        if (localResult.handledLocally) {
            remember("user", input)
            remember("assistant", localResult.text)
            callback(localResult.text, true)
            return
        }
        if (!gemini.isConfigured()) {
            callback("Main samajh rahi hoon, Boss. Is task ke liye online AI brain configure nahi hai. App Settings mein Gemini API key add kar sakte hain.", false)
            return
        }
        executor.execute {
            val history = loadHistory()
            val result = gemini.ask(input, history)
            val answer = result.getOrElse { "Online brain abhi available nahi hai. Main local mode mein hoon, Boss." }
            remember("user", input)
            remember("assistant", answer)
            callback(answer, false)
        }
    }

    private fun remember(role: String, text: String) {
        val items = loadHistory().toMutableList()
        items.add(role to text.take(1200))
        val trimmed = items.takeLast(12)
        val encoded = trimmed.joinToString("\n") { "${it.first}|${it.second.replace("\\n", " ")}" }
        historyPrefs.edit().putString("history", encoded).apply()
    }

    private fun loadHistory(): List<Pair<String, String>> = historyPrefs.getString("history", "").orEmpty()
        .lineSequence().mapNotNull {
            val p = it.indexOf('|')
            if (p <= 0) null else it.substring(0, p) to it.substring(p + 1)
        }.toList()
}
