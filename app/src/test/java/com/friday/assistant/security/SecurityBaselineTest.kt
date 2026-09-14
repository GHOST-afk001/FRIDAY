package com.friday.assistant.security

import com.friday.assistant.commands.FridayAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityBaselineTest {
    @Test
    fun speakerEnrollmentAndSameAudioMatch() {
        val verifier = SpeakerVerificationBaseline()
        val audio = syntheticVoice(32000, 220f)
        assertTrue(verifier.enroll(audio))
        val result = verifier.verify(audio)
        assertTrue(result.matched)
        assertTrue(result.similarity > 0.99f)
        assertEquals("MATCH", result.reason)
    }

    @Test
    fun speakerVerifierRejectsMissingProfile() {
        val verifier = SpeakerVerificationBaseline()
        val result = verifier.verify(syntheticVoice(3200, 180f))
        assertFalse(result.matched)
        assertEquals("NO_PROFILE", result.reason)
    }

    @Test
    fun speakerVerifierRejectsInsufficientAudio() {
        val verifier = SpeakerVerificationBaseline()
        val audio = syntheticVoice(32000, 220f)
        assertTrue(verifier.enroll(audio))
        val result = verifier.verify(ShortArray(100))
        assertFalse(result.matched)
        assertEquals("INSUFFICIENT_AUDIO", result.reason)
    }

    @Test
    fun actionResultNeverCallsPolicyApprovalExecutionSuccess() {
        val validator = ActionResultValidator()
        val result = validator.fromPolicy(ActionPolicyValidator.Outcome.Approved(FridayAction.Calculator))
        assertEquals(ActionResultValidator.Status.NOT_AVAILABLE, result.status)
        assertFalse(result.verified)
    }

    @Test
    fun actionResultDistinguishesObservedCompletionFromExternalHandoff() {
        val validator = ActionResultValidator()
        val action = FridayAction.Settings
        val completed = validator.fromExecution(action, ActionResultValidator.ExecutionObservation.EXECUTED_SUCCESSFULLY, "Operation completed")
        assertEquals(ActionResultValidator.Status.SUCCESS, completed.status)
        assertTrue(completed.verified)

        val handedOff = validator.fromExecution(action, ActionResultValidator.ExecutionObservation.HANDED_OFF, "Android accepted the request")
        assertEquals(ActionResultValidator.Status.HANDED_OFF, handedOff.status)
        assertFalse(handedOff.verified)
    }

    @Test
    fun actionResultMapsFailureAndPermissionStatesExactly() {
        val validator = ActionResultValidator()
        val action = FridayAction.Settings
        assertEquals(ActionResultValidator.Status.NEEDS_PERMISSION, validator.fromExecution(action, ActionResultValidator.ExecutionObservation.PERMISSION_REQUIRED, "Permission required").status)
        assertEquals(ActionResultValidator.Status.NOT_AVAILABLE, validator.fromExecution(action, ActionResultValidator.ExecutionObservation.NOT_AVAILABLE, "Target unavailable").status)
        assertEquals(ActionResultValidator.Status.FAILED, validator.fromExecution(action, ActionResultValidator.ExecutionObservation.FAILED, "Android reported failure").status)
        assertEquals(ActionResultValidator.Status.FAILED, validator.fromExecution(action, ActionResultValidator.ExecutionObservation.NOT_EXECUTED, "Execution did not start").status)
    }

    @Test
    fun confirmationIsRepresentedAsConfirmationNotSuccess() {
        val validator = ActionResultValidator()
        val outcome = ActionPolicyValidator().validate(FridayAction.DialContact("Rahul"))
        val result = validator.fromPolicy(outcome)
        assertEquals(ActionResultValidator.Status.NEEDS_CONFIRMATION, result.status)
        assertFalse(result.verified)
    }

    private fun syntheticVoice(size: Int, frequency: Float): ShortArray {
        val sampleRate = 16000f
        return ShortArray(size) { i ->
            (kotlin.math.sin(2.0 * Math.PI * frequency * i / sampleRate) * 12000).toInt().toShort()
        }
    }
}
