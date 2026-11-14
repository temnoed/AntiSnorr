package dev.antisnorr.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process bus between the detection service and the UI.
 *
 * Keeping it this simple means no binder, no LifecycleService and no leaks:
 * the service only writes, the activity only reads.
 */
object MonitorBus {
    enum class Status { IDLE, LISTENING, SNORE, ERROR }

    data class Event(
        val timeMs: Long,
        val peakProbability: Float,
        val durationMs: Long,
    )

    data class State(
        val running: Boolean = false,
        val status: Status = Status.IDLE,
        val snoreProbability: Float = 0f,
        val topLabel: String = "",
        val topProbability: Float = 0f,
        val rmsDb: Float = -120f,
        val events: List<Event> = emptyList(),
        val error: String? = null,
    ) {
        companion object {
            const val MAX_EVENTS = 20
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(transform: (State) -> State) {
        _state.update(transform)
    }

    fun addEvent(event: Event) {
        _state.update { current ->
            current.copy(
                events = (listOf(event) + current.events).take(State.MAX_EVENTS),
            )
        }
    }

    fun reset() {
        _state.update {
            State(events = it.events)
        }
    }
}
