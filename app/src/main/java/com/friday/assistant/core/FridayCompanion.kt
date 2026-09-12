package com.friday.assistant.core

/** Stable boundary between the portable AI core and a platform adapter. */
interface FridayPlatform {
    fun execute(action: Any): ExecutionResult
}

data class ExecutionResult(
    val success: Boolean,
    val message: String
)

/**
 * Companion behavior is deliberately separate from device control.
 * This keeps emotional conversation from requiring Android APIs.
 */
class FridayCompanion(private val core: UltronCore = UltronCore()) {
    fun contextFor(input: String, history: List<Pair<String, String>>): CoreContext = core.prepare(input, history)
}
