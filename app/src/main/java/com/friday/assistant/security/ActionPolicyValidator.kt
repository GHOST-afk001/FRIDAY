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

    fun validate(action: FridayAction): Outcome = when (action) {
        is FridayAction.DialNumber -> {
            val normalized = action.number.filter { it.isDigit() || it == '+' }
            if (normalized.count { it.isDigit() } !in 7..15) {
                Outcome.Rejected("The phone number does not look valid.")
            } else {
                Outcome.RequiresConfirmation(action.copy(number = normalized), "I need your confirmation before opening the dialer.")
            }
        }
        is FridayAction.DialContact -> {
            if (action.name.trim().isBlank()) Outcome.Rejected("I need a contact name before calling.")
            else Outcome.RequiresConfirmation(action, "I need your confirmation before opening the dialer.")
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
                    "I need your confirmation before opening the SMS composer."
                )
            }
        }
        else -> Outcome.Approved(action)
    }
}
