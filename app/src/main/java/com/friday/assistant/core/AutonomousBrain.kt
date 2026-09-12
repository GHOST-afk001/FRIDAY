package com.friday.assistant.core

/**
 * Platform-neutral decision layer. It never touches Android APIs or executes actions.
 * It decides how FRIDAY should reason about the next turn before the model answers.
 */
class AutonomousBrain {
    fun decide(input: String, history: List<Pair<String, String>>): BrainDecision {
        val value = input.trim().lowercase()
        val recent = history.takeLast(6)
        val contextHint = when {
            recent.isEmpty() -> "No previous conversation context is available."
            else -> "Use the recent conversation context when it is relevant; do not invent missing facts."
        }

        val mode = when {
            value.containsAny("help", "madad", "kya karu", "what should i do", "suggest") -> DecisionMode.PLAN
            value.containsAny("why", "kyu", "kaise", "how", "explain", "compare") -> DecisionMode.REASON
            value.containsAny("bore", "boring", "mann nahi", "akela", "lonely", "बोर", "अकेला") -> DecisionMode.CONVERSE
            value.containsAny("urgent", "emergency", "danger", "khatre", "help me") -> DecisionMode.SAFETY
            else -> DecisionMode.ANSWER
        }

        return BrainDecision(
            mode = mode,
            guidance = when (mode) {
                DecisionMode.PLAN -> "Infer the user's goal and propose the smallest useful next steps. If an important detail is missing, ask one focused question instead of guessing."
                DecisionMode.REASON -> "Reason step-by-step internally, then give a clear useful answer. Separate facts from assumptions."
                DecisionMode.CONVERSE -> "Prioritize natural human conversation and emotional context. Do not force a task."
                DecisionMode.SAFETY -> "Treat this as safety-sensitive. Stay calm, gather only essential information, and never claim an emergency action happened unless Android confirms it."
                DecisionMode.ANSWER -> "Understand the goal first. Choose the most useful response rather than mechanically answering keywords."
            } + " $contextHint"
        )
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it, ignoreCase = true) }
}

data class BrainDecision(
    val mode: DecisionMode,
    val guidance: String
)

enum class DecisionMode { ANSWER, PLAN, REASON, CONVERSE, SAFETY }
