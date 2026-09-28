package com.friday.assistant.runtime

import com.friday.assistant.commands.FridayAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FridaySafetyPolicyTest {
    @Test fun typingIsTrusted() {
        assertFalse(FridaySafetyPolicy.requiresConfirmation(FridayAction.AccessibilityCommand("type hello")))
    }

    @Test fun scrollingIsTrusted() {
        assertFalse(FridaySafetyPolicy.requiresConfirmation(FridayAction.AccessibilityCommand("scroll up")))
    }

    @Test fun ordinaryClickIsTrusted() {
        assertFalse(FridaySafetyPolicy.requiresConfirmation(FridayAction.AccessibilityCommand("click search")))
    }

    @Test fun sendClickIsGuarded() {
        assertTrue(FridaySafetyPolicy.requiresConfirmation(FridayAction.AccessibilityCommand("click send")))
    }

    @Test fun callsRemainGuarded() {
        assertTrue(FridaySafetyPolicy.requiresConfirmation(FridayAction.DialContact("Rahul")))
    }
}
