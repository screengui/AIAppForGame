package com.gameagent.core.validation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class EmergencyStopState(
    val engaged: Boolean = false,
    val reason: String? = null,
    val engagedAtMs: Long = 0L,
)

/**
 * A single latched kill switch.
 *
 * Properties that matter for safety:
 *  - **Latching.** Once engaged it stays engaged until explicitly [reset], so a burst of frames
 *    cannot "un-stop" the app.
 *  - **Immediate.** The action validator reads it synchronously on every decision, so no action is
 *    executed after the switch is thrown, even if the UI has not repainted yet.
 *  - **Local.** It never depends on a network call, coroutine dispatch or AI response, so it works
 *    even when the pipeline is wedged.
 *  - **Observable.** The UI renders [state] directly.
 */
class EmergencyStop(
    private var clock: () -> Long = System::currentTimeMillis,
) {
    private val engagedFlag = AtomicBoolean(false)

    private val mutableState = MutableStateFlow(EmergencyStopState())
    val state: StateFlow<EmergencyStopState> = mutableState.asStateFlow()

    /** @return true when this call actually tripped the switch (i.e. it was not already engaged). */
    fun engage(reason: String): Boolean {
        if (!engagedFlag.compareAndSet(false, true)) return false
        mutableState.value = EmergencyStopState(engaged = true, reason = reason, engagedAtMs = clock())
        return true
    }

    fun reset(): Boolean {
        if (!engagedFlag.compareAndSet(true, false)) return false
        mutableState.value = EmergencyStopState()
        return true
    }

    val isEngaged: Boolean get() = engagedFlag.get()
}
