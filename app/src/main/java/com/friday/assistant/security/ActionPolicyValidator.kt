package com.friday.assistant.security

import com.friday.assistant.commands.FridayAction

/**
 * Validates local actions immediately before execution.
 * The validator never executes an action and treats malformed or ambiguous
 * user-facing actions as unsafe by default.
 */
class ActionPolicyValidator {
    sealed interface Outcome {
        data class Approved(val action: FridayAction) : Outcome
        data class RequiresConfirmation(val action: FridayAction, val prompt: String) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    companion object {
        private const val MAX_SEQUENCE_DEPTH = 5
    }

    fun validate(action: FridayAction): Outcome = when (action) {
        is FridayAction.Sequence -> validateSequence(action)
        is FridayAction.DialNumber -> {
            val normalized = action.number.filter { it.isDigit() || it == '+' }
            if (normalized.count { it.isDigit() } !in 7..15) {
                Outcome.Rejected("The phone number does not look valid.")
            } else {
                Outcome.RequiresConfirmation(action.copy(number = normalized), "Please confirm the phone call before I place it.")
            }
        }
        is FridayAction.DialContact -> {
            if (action.name.trim().isBlank()) Outcome.Rejected("I need a contact name before calling.")
            else Outcome.RequiresConfirmation(action, "Please confirm the call before I place it.")
        }
        is FridayAction.SmsContact -> {
            val name = action.name.trim()
            val body = action.message.trim()
            when {
                name.isBlank() -> Outcome.Rejected("I need a contact name before preparing the message.")
                body.isBlank() -> Outcome.Rejected("I need the message text before preparing SMS.")
                body.length > 4000 -> Outcome.Rejected("That message is too long for a safe SMS hand-off.")
                else -> Outcome.RequiresConfirmation(
                    action.copy(name = name, message = body),
                    "Please confirm before I send this message."
                )
            }
        }
        is FridayAction.AccessibilityCommand -> {
            val command = action.command.trim()
            when {
                command.isBlank() -> Outcome.Rejected("I need an automation command before controlling another app.")
                requiresRiskyUiConfirmation(command) -> Outcome.RequiresConfirmation(
                    action,
                    "Please confirm before I perform that sensitive UI action."
                )
                else -> Outcome.Approved(action)
            }
        }
        else -> Outcome.Approved(action)
    }

    private fun requiresRiskyUiConfirmation(command: String): Boolean {
        val c = command.trim().lowercase()
        if (c.startsWith("whatsapp_message|")) return true
        if (c.startsWith("type ") || c.startsWith("text ") || c.startsWith("scroll ") || c.startsWith("swipe ")) return false
        if (c == "back" || c == "home" || c == "recents" || c == "open notifications") return false
        if (c.startsWith("click ") || c.startsWith("tap ")) {
            val target = c.substringAfter(' ').trim()
            return listOf("send", "submit", "purchase", "buy", "pay", "delete", "remove", "confirm", "transfer", "order", "post", "publish", "logout")
                .any { word -> Regex("(?<![a-z0-9])" + Regex.escape(word) + "(?![a-z0-9])").containsMatchIn(target) }
        }
        return false
    }

    private fun validateSequence(sequence: FridayAction.Sequence): Outcome {
        val normalized = ArrayList<FridayAction>()
        if (!flatten(sequence.actions, normalized)) {
            return Outcome.Rejected("The action plan is nested too deeply.")
        }
        if (normalized.isEmpty()) return Outcome.Rejected("The action plan is empty.")

        val validated = ArrayList<FridayAction>(normalized.size)
        var confirmationPrompt: String? = null
        for (action in normalized) {
            when (val outcome = validate(action)) {
                is Outcome.Rejected -> return outcome
                is Outcome.RequiresConfirmation -> {
                    validated += outcome.action
                    if (confirmationPrompt == null) confirmationPrompt = outcome.prompt
                }
                is Outcome.Approved -> validated += outcome.action
            }
        }

        val safePlan = FridayAction.Sequence(validated)
        return if (confirmationPrompt != null) {
            Outcome.RequiresConfirmation(
                safePlan,
                "This plan includes an action that needs your confirmation before anything is executed."
            )
        } else {
            Outcome.Approved(safePlan)
        }
    }

    private fun flatten(actions: List<FridayAction>, normalized: MutableList<FridayAction>, depth: Int = 0): Boolean {
        if (depth >= MAX_SEQUENCE_DEPTH) return false
        for (act in actions) {
            if (act is FridayAction.Sequence) {
                if (!flatten(act.actions, normalized, depth + 1)) return false
            } else {
                normalized.add(act)
            }
        }
        return true
    }
}
