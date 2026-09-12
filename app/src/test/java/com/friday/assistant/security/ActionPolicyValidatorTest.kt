package com.friday.assistant.security

import com.friday.assistant.commands.FridayAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionPolicyValidatorTest {
    private val policy = ActionPolicyValidator()

    @Test
    fun blankContactIsRejected() {
        val result = policy.validate(FridayAction.DialContact("   "))
        assertIs<ActionPolicyValidator.Outcome.Rejected>(result)
    }

    @Test
    fun contactCallRequiresConfirmation() {
        val result = policy.validate(FridayAction.DialContact("Rahul"))
        assertIs<ActionPolicyValidator.Outcome.RequiresConfirmation>(result)
    }

    @Test
    fun invalidNumberIsRejected() {
        val result = policy.validate(FridayAction.DialNumber("123"))
        assertIs<ActionPolicyValidator.Outcome.Rejected>(result)
    }

    @Test
    fun validNumberRequiresConfirmationAndNormalizes() {
        val result = policy.validate(FridayAction.DialNumber("+91 98765-43210"))
        val confirmation = assertIs<ActionPolicyValidator.Outcome.RequiresConfirmation>(result)
        assertEquals("+919876543210", (confirmation.action as FridayAction.DialNumber).number)
    }

    @Test
    fun blankSmsIsRejected() {
        val result = policy.validate(FridayAction.SmsContact("Rahul", "   "))
        assertIs<ActionPolicyValidator.Outcome.Rejected>(result)
    }

    @Test
    fun ordinaryActionsRemainApproved() {
        val result = policy.validate(FridayAction.YouTube)
        assertIs<ActionPolicyValidator.Outcome.Approved>(result)
    }
}
