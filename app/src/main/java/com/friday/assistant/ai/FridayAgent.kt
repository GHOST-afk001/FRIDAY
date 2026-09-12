package com.friday.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.core.FridayCompanion
import com.friday.assistant.core.Emotion
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class FridayAgent(context: Context) {
    private val appContext = context.applicationContext
    private val local = FridayCommandProcessor()
    private val gemini = GeminiProvider(appContext)
    private val companion = FridayCompanion()
    private val historyPrefs = appContext.getSharedPreferences("friday_memory", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryLock = Any()
    private val requestGeneration = AtomicLong(0L)
    @Volatile private var closed = false
    @Volatile private var activeRequest: Future<*>? = null

    fun configureApiKey(key: String) { if (!closed) gemini.setApiKey(key) }
    fun hasApiKey() = !closed && gemini.isConfigured()
    fun clearApiKey() { if (!closed) gemini.clearApiKey() }

    fun handle(input: String, callback: (String, Boolean) -> Unit) {
        if (closed) return
        val localResult = local.process(input)
        if (localResult.handledLocally) {
            remember("user", input)
            remember("assistant", localResult.text)
            if (!closed) callback(localResult.text, true)
            return
        }

        val history = loadHistory()
        val context = companion.contextFor(input, history)
        companionReply(context.emotion)?.let { answer ->
            remember("user", input)
            remember("assistant", answer)
            if (!closed) callback(answer, false)
            return
        }

        if (!gemini.isConfigured()) {
            if (!closed) callback("Main tumhari baat samajh rahi hoon, Boss. Online brain abhi configure nahi hai, lekin hum normal baat kar sakte hain—bas batao kya chal raha hai.", false)
            return
        }

        val myGeneration = requestGeneration.incrementAndGet()
        activeRequest?.cancel(true)
        gemini.cancel()
        activeRequest = executor.submit {
            try {
                if (closed || Thread.currentThread().isInterrupted || requestGeneration.get() != myGeneration) return@submit
                val enrichedInput = "${context.systemGuidance}\nUser message: $input"
                val result = gemini.ask(enrichedInput, history)
                if (closed || Thread.currentThread().isInterrupted || requestGeneration.get() != myGeneration) return@submit
                val answer = result.getOrElse { "Online brain abhi available nahi hai. Main local mode mein hoon, Boss." }
                remember("user", input)
                remember("assistant", answer)
                mainHandler.post {
                    if (!closed && requestGeneration.get() == myGeneration) callback(answer, false)
                }
            } finally {
                if (requestGeneration.get() == myGeneration) activeRequest = null
            }
        }
    }

    private fun companionReply(emotion: Emotion): String? = when (emotion) {
        Emotion.BORED -> "Bore ho rahe ho Boss? 😄 Chalo mere saath thodi baat karte hain. Main tumhe koi interesting topic de sakti hoon, random game khel sakti hoon, ya bas bakchodi karte hain."
        Emotion.SAD -> "Hey Boss… agar mann heavy hai toh bol do. Main sun rahi hoon. Advice chahiye ya bas kisi se baat karni hai, dono chalega."
        Emotion.STRESSED -> "Thoda slow, Boss. Ek kaam ek time par. Batao sabse zyada tension kis cheez ki hai?"
        Emotion.ANGRY -> "Gussa aa raha hai, Boss? Pehle batao kya hua. Main bina judge kiye sunungi."
        Emotion.EXCITED, Emotion.CASUAL -> null
    }

    fun close() {
        if (closed) return
        closed = true
        requestGeneration.incrementAndGet()
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
            val trimmed = items.takeLast(20)
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
