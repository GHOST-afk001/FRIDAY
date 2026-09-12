package com.friday.assistant.core

import com.friday.assistant.commands.FridayAction

/** Strongly typed boundary between the portable core and a platform adapter. */
interface FridayPlatform {
    fun execute(action: FridayAction): ExecutionResult
}

data class ExecutionResult(
    val success: Boolean,
    val message: String
)

/**
 * Companion behavior is deliberately separate from device control.
 * This keeps emotional conversation free of Android APIs while the
 * platform adapter receives only the sealed FridayAction contract.
 */
class FridayCompanion(private val core: UltronCore = UltronCore()) {
    fun contextFor(input: String, history: List<Pair<String, String>>): CoreContext = core.prepare(input, history)
}
