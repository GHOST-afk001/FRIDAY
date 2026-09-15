package com.friday.assistant.core

/**
 * Describes a capability FRIDAY may use. This layer is deliberately platform/network agnostic:
 * providers and Android actions can be plugged in later without changing decision semantics.
 */
data class AgentToolDescriptor(
    val id: String,
    val purpose: String,
    val risk: ToolRisk = ToolRisk.LOW,
    val requiresConfirmation: Boolean = false
)

enum class ToolRisk { LOW, MEDIUM, HIGH }

data class ToolPlan(
    val tools: List<AgentToolDescriptor>,
    val requiresConfirmation: Boolean,
    val rationale: String
)

/**
 * Chooses the smallest useful set of capabilities from deterministic intent signals.
 * External APIs are not required here; descriptors become real providers later.
 */
class AgentToolPlanner(
    private val tools: List<AgentToolDescriptor> = DEFAULT_TOOLS
) {
    fun plan(intent: IntentUnderstanding): ToolPlan {
        // Clarification is a hard precondition: do not expose an executable tool plan
        // while required information is missing. This prevents a future executor from
        // accidentally acting on an incomplete request.
        if (intent.needsClarification) {
            return ToolPlan(
                tools = emptyList(),
                requiresConfirmation = true,
                rationale = "Required information is missing; clarify before selecting or executing a tool."
            )
        }

        val byId = tools.associateBy { it.id }
        val selected = when (intent.category) {
            IntentCategory.INFORMATION,
            IntentCategory.COMPARISON,
            IntentCategory.PLANNING -> listOfNotNull(byId["web.search"])
            IntentCategory.NAVIGATION -> listOfNotNull(byId["android.navigation"])
            IntentCategory.COMMUNICATION -> listOfNotNull(byId["android.communication"])
            IntentCategory.DEVICE_ACTION -> listOfNotNull(byId["android.device"])
            IntentCategory.SAFETY -> listOfNotNull(byId["android.safety"])
            IntentCategory.CONVERSATION,
            IntentCategory.EXPLANATION,
            IntentCategory.CREATIVE,
            IntentCategory.UNKNOWN -> emptyList()
        }
        val confirmation = selected.any { it.requiresConfirmation || it.risk == ToolRisk.HIGH }
        val rationale = if (selected.isEmpty()) {
            "No external capability is required; answer or reason locally/with the model."
        } else {
            "Use only the capabilities relevant to the detected intent; do not call unrelated tools."
        }
        return ToolPlan(selected, confirmation, rationale)
    }

    companion object {
        val DEFAULT_TOOLS = listOf(
            AgentToolDescriptor("web.search", "Retrieve current web information safely"),
            AgentToolDescriptor("android.navigation", "Open or navigate using Android-supported navigation", ToolRisk.MEDIUM),
            AgentToolDescriptor("android.communication", "Prepare or perform supported communication actions", ToolRisk.HIGH, true),
            AgentToolDescriptor("android.device", "Perform supported device controls", ToolRisk.MEDIUM),
            AgentToolDescriptor("android.safety", "Handle safety-sensitive Android actions", ToolRisk.HIGH, true)
        )
    }
}
