package com.friday.assistant.core

/**
 * Platform-neutral decision layer. It never touches Android APIs or executes actions.
 * It combines deterministic high-confidence intent signals with conversation context.
 */
class AutonomousBrain(
    private val intentEngine: IntentUnderstandingEngine = IntentUnderstandingEngine()
) {
    fun decide(input: String, history: List<Pair<String, String>>): BrainDecision {
        val understanding = intentEngine.understand(input, history)
        val recent = history.takeLast(6)
        val contextHint = when {
            recent.isEmpty() -> "No previous conversation context is available."
            else -> "Use recent conversation context when relevant; do not invent missing facts."
        }

        val mode = when (understanding.category) {
            IntentCategory.SAFETY -> DecisionMode.SAFETY
            IntentCategory.PLANNING -> DecisionMode.PLAN
            IntentCategory.EXPLANATION, IntentCategory.COMPARISON -> DecisionMode.REASON
            IntentCategory.CONVERSATION -> DecisionMode.CONVERSE
            else -> DecisionMode.ANSWER
        }

        val guidance = when (mode) {
            DecisionMode.PLAN -> "Infer the user's goal and constraints, then propose the smallest useful next steps."
            DecisionMode.REASON -> "Reason carefully internally, separate facts from assumptions, and compare trade-offs when relevant."
            DecisionMode.CONVERSE -> "Prioritize natural human conversation and emotional context. Do not force a task."
            DecisionMode.SAFETY -> "Treat this as safety-sensitive. Stay calm, gather only essential information, and never claim an emergency action happened unless Android confirms it."
            DecisionMode.ANSWER -> "Understand the goal first and answer naturally rather than mechanically matching keywords."
        }

        return BrainDecision(
            mode = mode,
            intent = understanding,
            guidance = "$guidance $contextHint",
            shouldClarify = understanding.needsClarification,
            clarification = if (understanding.needsClarification) {
                "Ask one concise question for: ${understanding.missing.joinToString(", ")}. Do not ask for information that is not needed."
            } else null
        )
    }
}

data class BrainDecision(
    val mode: DecisionMode,
    val intent: IntentUnderstanding,
    val guidance: String,
    val shouldClarify: Boolean,
    val clarification: String?
)

enum class DecisionMode { ANSWER, PLAN, REASON, CONVERSE, SAFETY }
