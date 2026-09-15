package com.friday.assistant.security

import com.friday.assistant.commands.FridayAction

/**
 * Converts policy and Android execution observations into one strict, truthful result model.
 * This layer never infers completion from intent or a successful hand-off.
 */
class ActionResultValidator {
    enum class Status {
        SUCCESS,
        HANDED_OFF,
        NEEDS_CONFIRMATION,
        NEEDS_PERMISSION,
        NOT_AVAILABLE,
        FAILED
    }

    enum class ExecutionObservation {
        /** The underlying operation's completion was actually observed. */
        EXECUTED_SUCCESSFULLY,
        /** Android accepted/started the requested hand-off, but completion is external or unobservable. */
        HANDED_OFF,
        PERMISSION_REQUIRED,
        NOT_AVAILABLE,
        FAILED,
        NOT_EXECUTED
    }

    data class Result(
        val action: String,
        val status: Status,
        val verified: Boolean,
        val detail: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun fromPolicy(outcome: ActionPolicyValidator.Outcome): Result = when (outcome) {
        is ActionPolicyValidator.Outcome.Approved -> Result(
            action = outcome.action.javaClass.simpleName,
            status = Status.NOT_AVAILABLE,
            verified = false,
            detail = "Action is policy-approved but has not been executed."
        )
        is ActionPolicyValidator.Outcome.RequiresConfirmation -> Result(
            action = outcome.action.javaClass.simpleName,
            status = Status.NEEDS_CONFIRMATION,
            verified = false,
            detail = outcome.prompt
        )
        is ActionPolicyValidator.Outcome.Rejected -> Result(
            action = "UNKNOWN",
            status = Status.FAILED,
            verified = false,
            detail = outcome.reason
        )
    }

    fun fromExecution(action: FridayAction, observation: ExecutionObservation, detail: String): Result {
        val status = when (observation) {
            ExecutionObservation.EXECUTED_SUCCESSFULLY -> Status.SUCCESS
            ExecutionObservation.HANDED_OFF -> Status.HANDED_OFF
            ExecutionObservation.PERMISSION_REQUIRED -> Status.NEEDS_PERMISSION
            ExecutionObservation.NOT_AVAILABLE -> Status.NOT_AVAILABLE
            ExecutionObservation.FAILED,
            ExecutionObservation.NOT_EXECUTED -> Status.FAILED
        }
        return Result(
            action = action.javaClass.simpleName,
            status = status,
            verified = observation == ExecutionObservation.EXECUTED_SUCCESSFULLY,
            detail = detail
        )
    }
}
