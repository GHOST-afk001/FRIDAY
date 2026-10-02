package com.friday.assistant.runtime

import com.friday.assistant.commands.FridayAction
import java.util.Locale

object FridaySafetyPolicy {
    fun requiresConfirmation(action: FridayAction): Boolean = when (action) {
        is FridayAction.DialContact, is FridayAction.DialNumber,
        is FridayAction.SmsContact, FridayAction.EmergencySos -> true
        is FridayAction.AccessibilityCommand -> requiresUiConfirmation(action.command)
        is FridayAction.Sequence -> action.actions.any(::requiresConfirmation)
        else -> false
    }

    private fun requiresUiConfirmation(command: String): Boolean {
        val c = command.trim().lowercase(Locale.ROOT)
        if (c.startsWith("type ") || c.startsWith("scroll ") || c.startsWith("swipe ")) return false
        if (c == "back" || c == "home" || c == "recents") return false
        if (c.startsWith("click ") || c.startsWith("tap ")) {
            return listOf("send","submit","purchase","buy","pay","delete","remove","confirm","transfer","order","post","publish","logout").any(c::contains)
        }
        return false
    }

    fun describeSensitive(action: FridayAction): String? = when (action) {
        is FridayAction.DialContact -> "Call ${action.name}"
        is FridayAction.DialNumber -> "Call ${action.number}"
        is FridayAction.SmsContact -> "Send message to ${action.name}"
        FridayAction.EmergencySos -> "Start emergency call"
        else -> null
    }
}