package com.friday.assistant.runtime

import com.friday.assistant.commands.FridayAction

object FridaySafetyPolicy {
    fun requiresConfirmation(action: FridayAction): Boolean = when (action) {
        is FridayAction.DialContact, is FridayAction.DialNumber,
        is FridayAction.SmsContact, FridayAction.EmergencySos -> true
        is FridayAction.AccessibilityCommand -> {
            val c = action.command.lowercase()
            c.startsWith("type ") || c.startsWith("click ") || c.startsWith("tap ") ||
                c == "home" || c == "back" || c == "recents"
        }
        is FridayAction.Sequence -> action.actions.any(::requiresConfirmation)
        else -> false
    }

    fun describeSensitive(action: FridayAction): String? = when (action) {
        is FridayAction.DialContact -> "Call ${action.name}"
        is FridayAction.DialNumber -> "Call ${action.number}"
        is FridayAction.SmsContact -> "Send message to ${action.name}"
        FridayAction.EmergencySos -> "Start emergency call"
        else -> null
    }
}
