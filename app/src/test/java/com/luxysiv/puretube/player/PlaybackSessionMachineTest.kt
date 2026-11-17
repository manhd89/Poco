package com.luxysiv.puretube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra bảng chuyển pha tường minh và guard của state machine phiên phát. */
class PlaybackSessionMachineTest {

    @Test
    fun `starts idle by default`() {
        assertEquals(SessionPhase.IDLE, PlaybackSessionMachine().current)
    }

    @Test
    fun `happy path idle resolving ready closed`() {
        val machine = PlaybackSessionMachine()

        assertTrue(machine.transitionTo(SessionPhase.RESOLVING))
        assertTrue(machine.transitionTo(SessionPhase.READY))
        assertTrue(machine.transitionTo(SessionPhase.CLOSED))
        assertEquals(SessionPhase.CLOSED, machine.current)
        assertEquals(SessionPhase.CLOSED, machine.phase.value)
    }

    @Test
    fun `failure can retry back into resolving`() {
        val machine = PlaybackSessionMachine()

        assertTrue(machine.transitionTo(SessionPhase.RESOLVING))
        assertTrue(machine.transitionTo(SessionPhase.FAILED))
        assertTrue(machine.transitionTo(SessionPhase.RESOLVING))
        assertEquals(SessionPhase.RESOLVING, machine.current)
    }

    @Test
    fun `invalid transitions are ignored without throwing`() {
        val machine = PlaybackSessionMachine()

        // IDLE không thể nhảy thẳng sang READY.
        assertFalse(machine.transitionTo(SessionPhase.READY))
        assertEquals(SessionPhase.IDLE, machine.current)
    }

    @Test
    fun `closed session rejects ready but accepts reopen`() {
        val machine = PlaybackSessionMachine(SessionPhase.CLOSED)

        assertFalse(machine.transitionTo(SessionPhase.READY))
        assertTrue(machine.transitionTo(SessionPhase.RESOLVING))
    }

    @Test
    fun `error from idle is allowed`() {
        val machine = PlaybackSessionMachine()

        assertTrue(machine.transitionTo(SessionPhase.FAILED))
        assertEquals(SessionPhase.FAILED, machine.current)
    }
}
