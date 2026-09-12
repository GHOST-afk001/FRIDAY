package com.friday.assistant.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.core.AutonomousBrain
import com.friday.assistant.core.FridayCompanion
import com.friday.assistant.core.Emotion
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * FRIDAY's model-facing brain. Local commands remain authoritative; Gemini is used for
 * open-ended reasoning, planning, context and natural conversation.
 */
class FridayAgent(context: Context) {
    private val appContext = context.applicationContext
    private val local = FridayCommandProcessor()
    private val gemini = GeminiProvider(appContext)
    private val companion = FridayCompanion()
    private val autonomousBrain = AutonomousBrain()
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
        val decision = autonomousBrain.decide(input, history)

        // Local emotional fallbacks are used only when the online brain is unavailable.
        if (!gemini.isConfigured()) {
            companionReply(context.emotion)?.let { answer ->
                remember("user", input)
                remember("assistant", answer)
                if (!closed) callback(answer, false)
                return
            }
            if (!closed) callback("Main tumhari baat samajh rahi hoon, Boss. AI brain abhi configure nahi hai, lekin local FRIDAY core active hai.", false)
            return
        }

        val myGeneration = requestGeneration.incrementAndGet()
        activeRequest?.cancel(true)
        gemini.cancel()
        activeRequest = executor.submit {
            try {
                if (closed || Thread.currentThread().isInterrupted || requestGeneration.get() != myGeneration) return@submit
                val enrichedInput = buildString {
                    append(context.systemGuidance)
                    append("\nDecision mode: ")
                    append(decision.mode.name)
                    append("\nDecision guidance: ")
                    append(decision.guidance)
                    append("\nUser message: ")
                    append(input)
                }
                val result = gemini.ask(enrichedInput, history)
                if (closed || Thread.currentThread().isInterrupted || requestGeneration.get() != myGeneration) return@submit
                val answer = result.getOrElse { "AI brain se connection nahi ho paya. Local FRIDAY core abhi active hai, Boss." }
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
        Emotion.BORED -> "Bore ho rahe ho Boss? 😄 Chalo baat karte hain."
        Emotion.SAD -> "Hey Boss… mann heavy hai toh main sun rahi hoon."
        Emotion.STRESSED -> "Thoda slow, Boss. Ek kaam ek time par."
        Emotion.ANGRY -> "Gussa aa raha hai, Boss? Batao kya hua."
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
            val trimmed = items.takeLast(30)
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
