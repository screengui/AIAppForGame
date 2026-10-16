package com.gameagent.core.session

import com.gameagent.core.model.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionStateMachineTest {

    @Test
    fun `documented happy path is accepted`() {
        val machine = SessionStateMachine()
        assertEquals(SessionState.IDLE, machine.current())

        assertTrue(machine.transitionTo(SessionState.REQUESTING_PERMISSION, "ask").accepted)
        assertTrue(machine.transitionTo(SessionState.CAPTURING, "granted").accepted)
        assertTrue(machine.transitionTo(SessionState.READY, "capture up").accepted)
        assertTrue(machine.transitionTo(SessionState.RUNNING, "start").accepted)
        assertTrue(machine.transitionTo(SessionState.PROCESSING, "frame").accepted)
        assertTrue(machine.transitionTo(SessionState.RUNNING, "frame done").accepted)
        assertTrue(machine.transitionTo(SessionState.PAUSED, "background").accepted)
        assertTrue(machine.transitionTo(SessionState.RUNNING, "foreground").accepted)
        assertTrue(machine.transitionTo(SessionState.STOPPING, "stop").accepted)
        assertTrue(machine.transitionTo(SessionState.IDLE, "stopped").accepted)
    }

    @Test
    fun `permission denial returns to idle`() {
        val machine = SessionStateMachine()
        machine.transitionTo(SessionState.REQUESTING_PERMISSION)
        assertTrue(machine.transitionTo(SessionState.IDLE, "denied").accepted)
        assertEquals(SessionState.IDLE, machine.current())
    }

    @Test
    fun `illegal transitions are refused and do not change state`() {
        val machine = SessionStateMachine()
        val illegal = machine.transitionTo(SessionState.RUNNING, "cannot start from idle")
        assertIs<TransitionResult.Invalid>(illegal)
        assertEquals(SessionState.IDLE, machine.current())

        machine.transitionTo(SessionState.REQUESTING_PERMISSION)
        machine.transitionTo(SessionState.CAPTURING)
        machine.transitionTo(SessionState.READY)
        machine.transitionTo(SessionState.RUNNING)
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.CAPTURING))
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.REQUESTING_PERMISSION))
        assertEquals(SessionState.RUNNING, machine.current())
    }

    @Test
    fun `stopping can only lead to idle or error`() {
        val machine = SessionStateMachine(SessionState.STOPPING)
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.RUNNING))
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.CAPTURING))
        assertTrue(machine.transitionTo(SessionState.ERROR, "cleanup failed").accepted)
    }

    @Test
    fun `self transitions are refused so repeated presses are visible`() {
        val machine = SessionStateMachine()
        machine.transitionTo(SessionState.REQUESTING_PERMISSION)
        machine.transitionTo(SessionState.CAPTURING)
        machine.transitionTo(SessionState.READY)
        machine.transitionTo(SessionState.RUNNING)

        val again = machine.transitionTo(SessionState.RUNNING, "start pressed twice")
        assertIs<TransitionResult.Invalid>(again)
        assertTrue(again.reason.contains("already in"), "reason was: ${again.reason}")
    }

    @Test
    fun `there is no way to reach running without capture resources`() {
        val machine = SessionStateMachine()
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.PROCESSING))
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.PAUSED))
        assertIs<TransitionResult.Invalid>(machine.transitionTo(SessionState.STOPPING))
    }

    @Test
    fun `error is recoverable through idle`() {
        val machine = SessionStateMachine(SessionState.ERROR)
        assertTrue(machine.transitionTo(SessionState.IDLE, "retry").accepted)
    }

    @Test
    fun `every state except idle requires capture resources as documented`() {
        val withoutResources = SessionState.entries.filterNot { it.holdsCaptureResources }.toSet()
        assertEquals(
            setOf(
                SessionState.IDLE,
                SessionState.REQUESTING_PERMISSION,
                SessionState.ERROR,
            ),
            withoutResources,
        )
    }

    @Test
    fun `only an active session allows input`() {
        val allows = SessionState.entries.filter { it.allowsInput }.toSet()
        assertEquals(setOf(SessionState.RUNNING, SessionState.PROCESSING), allows)
    }

    @Test
    fun `processing can only be entered from running`() {
        val sources = SessionState.entries.filter { SessionState.PROCESSING in SessionStateMachine.ALLOWED.getValue(it) }
        assertEquals(listOf(SessionState.RUNNING), sources)
    }

    @Test
    fun `history is bounded and ordered`() {
        val machine = SessionStateMachine()
        repeat(20) {
            machine.transitionTo(SessionState.REQUESTING_PERMISSION)
            machine.transitionTo(SessionState.IDLE)
        }
        val history = machine.history()
        assertTrue(history.size <= SessionStateMachine.HISTORY_CAPACITY)
        assertEquals(SessionState.IDLE, history.first().from)
        assertEquals(SessionState.REQUESTING_PERMISSION, history.first().to)
        assertEquals(SessionState.REQUESTING_PERMISSION, history.last().from)
        assertEquals(SessionState.IDLE, history.last().to)
        assertFalse(history.isEmpty())
    }

    @Test
    fun `state flow reflects the latest transition`() {
        val machine = SessionStateMachine()
        machine.transitionTo(SessionState.REQUESTING_PERMISSION)
        assertEquals(SessionState.REQUESTING_PERMISSION, machine.state.value)
    }
}
