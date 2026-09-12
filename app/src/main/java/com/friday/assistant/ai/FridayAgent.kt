package com.friday.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.friday.assistant.commands.FridayCommandProcessor
import java.util.concurrent.Executors
import java.util.concurrent.Future

class FridayAgent(context: Context) {
    private val appContext = context.applicationContext
    private val local = FridayCommandProcessor()
    private val gemini = GeminiProvider(appContext)
    private val historyPrefs = appContext.getSharedPreferences("friday_memory", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryLock = Any()
    @Volatile private var closed = false
    @Volatile private var activeRequest: Future<*>? = null

    fun configureApiKey(key: String) {
        if (!closed) gemini.setApiKey(key)
    }

    fun hasApiKey() = !closed && gemini.isConfigured()

    fun clearApiKey() = gemini.clearApiKey()

    fun handle(input: String, callback: (String, Boolean) -> Unit) {
        if (closed) return
        val localResult = local.process(input)
        if (localResult.handledLocally) {
            remember("user", input)
            remember("assistant", localResult.text)
            if (!closed) callback(localResult.text, true)
            return
        }
        if (!gemini.isConfigured()) {
            if (!closed) callback("Main samajh rahi hoon, Boss. Is task ke liye online AI brain configure nahi hai. App Settings mein Gemini API key add kar sakte hain.", false)
            return
        }
        activeRequest?.cancel(true)
        activeRequest = executor.submit {
            try {
                if (closed || Thread.currentThread().isInterrupted) return@submit
                val history = loadHistory()
                val result = gemini.ask(input, history)
                if (closed || Thread.currentThread().isInterrupted) return@submit
                val answer = result.getOrElse { "Online brain abhi available nahi hai. Main local mode mein hoon, Boss." }
                remember("user", input)
                remember("assistant", answer)
                mainHandler.post {
                    if (!closed) callback(answer, false)
                }
            } finally {
                activeRequest = null
            }
        }
    }

    /** Cancel network work and release the agent's executor when its voice session ends. */
    fun close() {
        if (closed) return
        closed = true
        activeRequest?.cancel(true)
        activeRequest = null
        gemini.cancel()
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun remember(role: String, text: String) {
        synchronized(memoryLock) {
            val items = loadHistoryLocked().toMutableList()
            items.add(role to text.take(1200))
            val trimmed = items.takeLast(12)
            val encoded = trimmed.joinToString("\n") { "${it.first}|${it.second.replace("\n", " ")}" }
            historyPrefs.edit().putString("history", encoded).apply()
        }
    }

    private fun loadHistory(): List<Pair<String, String>> = synchronized(memoryLock) { loadHistoryLocked() }

    private fun loadHistoryLocked(): List<Pair<String, String>> = historyPrefs.getString("history", "").orEmpty()
        .lineSequence().mapNotNull {
            val p = it.indexOf('|')
            if (p <= 0) null else it.substring(0, p) to it.substring(p + 1)
        }.toList()
}
