package com.friday.assistant.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single process-wide UI/runtime source of truth. Values contain no message bodies or secrets. */
data class FridayUiState(
    val stage: String = "IDLE",
    val detail: String = "FRIDAY ready",
    val healthy: Boolean = true,
    val audioAmplitude: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis()
)

object FridayStateFlow {
    private val _state = MutableStateFlow(FridayUiState())
    val state: StateFlow<FridayUiState> = _state.asStateFlow()

    fun updateRuntime(status: RuntimeStatus) {
        _state.update { current ->
            current.copy(
                stage = status.stage,
                detail = status.detail,
                healthy = status.healthy,
                updatedAt = status.updatedAt
            )
        }
    }

    fun updateAmplitude(value: Float) {
        val normalized = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        _state.update { it.copy(audioAmplitude = normalized) }
    }

    fun resetAmplitude() { updateAmplitude(0f) }
}
