package com.friday.assistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentToolPlannerTest {
    private val planner = AgentToolPlanner()

    @Test
    fun informationRequestsSelectWebSearchOnly() {
        val intent = IntentUnderstanding(IntentCategory.INFORMATION, 0.9f, emptyMap(), emptyList(), false)
        val plan = planner.plan(intent)

        assertEquals(listOf("web.search"), plan.tools.map { it.id })
        assertFalse(plan.requiresConfirmation)
    }

    @Test
    fun communicationRequiresConfirmation() {
        val intent = IntentUnderstanding(IntentCategory.COMMUNICATION, 0.9f, mapOf("target" to "Alice"), emptyList(), false)
        val plan = planner.plan(intent)

        assertEquals(listOf("android.communication"), plan.tools.map { it.id })
        assertTrue(plan.requiresConfirmation)
    }

    @Test
    fun missingInformationBlocksToolSelection() {
        val intent = IntentUnderstanding(IntentCategory.NAVIGATION, 0.9f, emptyMap(), listOf("destination"), true)
        val plan = planner.plan(intent)

        assertTrue(plan.tools.isEmpty())
        assertTrue(plan.requiresConfirmation)
        assertTrue(plan.rationale.contains("clarify", ignoreCase = true))
    }

    @Test
    fun conversationDoesNotCallExternalTools() {
        val intent = IntentUnderstanding(IntentCategory.CONVERSATION, 0.8f, emptyMap(), emptyList(), false)
        val plan = planner.plan(intent)

        assertTrue(plan.tools.isEmpty())
        assertFalse(plan.requiresConfirmation)
    }
}
