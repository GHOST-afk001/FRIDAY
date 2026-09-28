package com.friday.assistant.runtime

import com.friday.assistant.commands.FridayAction
import com.friday.assistant.commands.FridayResponse

class FridayActionPlanner {
    data class Plan(val actions: List<FridayAction>, val requiresConfirmation: Boolean, val explanation: String)

    fun plan(response: FridayResponse): Plan? {
        val action = response.action ?: return null
        val actions = when (action) {
            is FridayAction.Sequence -> action.actions
            else -> listOf(action)
        }
        val confirmation = response.needsConfirmation || actions.any(FridaySafetyPolicy::requiresConfirmation)
        return Plan(
            actions = actions,
            requiresConfirmation = confirmation,
            explanation = actions.mapNotNull(FridaySafetyPolicy::describeSensitive).joinToString(", ")
        )
    }
}
