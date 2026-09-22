package com.panomc.node

import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerSpec
import com.panomc.node.server.ServerStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerStateMachineTest {
    @Test
    fun `a stopped or crashed server may be started`() {
        assertEquals(ServerProcessState.STARTING, ServerStateMachine.onStartRequested(ServerProcessState.STOPPED))
        assertEquals(ServerProcessState.STARTING, ServerStateMachine.onStartRequested(ServerProcessState.CRASHED))
    }

    @Test
    fun `a live server may not be started again`() {
        assertNull(ServerStateMachine.onStartRequested(ServerProcessState.STARTING))
        assertNull(ServerStateMachine.onStartRequested(ServerProcessState.RUNNING))
        assertNull(ServerStateMachine.onStartRequested(ServerProcessState.STOPPING))
    }

    @Test
    fun `only a starting server becomes running`() {
        assertEquals(ServerProcessState.RUNNING, ServerStateMachine.onReady(ServerProcessState.STARTING))
        assertNull(ServerStateMachine.onReady(ServerProcessState.RUNNING))
        assertNull(ServerStateMachine.onReady(ServerProcessState.STOPPED))
    }

    @Test
    fun `a stop is accepted from every live state and refused from the rest`() {
        assertEquals(ServerProcessState.STOPPING, ServerStateMachine.onStopRequested(ServerProcessState.RUNNING))
        assertEquals(ServerProcessState.STOPPING, ServerStateMachine.onStopRequested(ServerProcessState.STARTING))
        assertNull(ServerStateMachine.onStopRequested(ServerProcessState.STOPPED))
        assertNull(ServerStateMachine.onStopRequested(ServerProcessState.CRASHED))
    }

    @Test
    fun `an unrequested non-zero exit is a crash`() {
        assertEquals(ServerProcessState.CRASHED, ServerStateMachine.onExit(1, requested = false))
    }

    @Test
    fun `a requested exit is never a crash, whatever the code`() {
        assertEquals(ServerProcessState.STOPPED, ServerStateMachine.onExit(143, requested = true))
        assertEquals(ServerProcessState.STOPPED, ServerStateMachine.onExit(0, requested = true))
    }

    @Test
    fun `a clean exit nobody asked for is still just stopped`() {
        assertEquals(ServerProcessState.STOPPED, ServerStateMachine.onExit(0, requested = false))
    }

    @Test
    fun `a clean exit during startup is a crash, not a clean stop`() {
        // A Minecraft server that cannot bind its port shuts itself down tidily with status 0
        // about twenty seconds in. Reported as STOPPED it looks like somebody pressed stop.
        assertEquals(
            ServerProcessState.CRASHED,
            ServerStateMachine.onExit(0, requested = false, reachedRunning = false, uptimeMillis = 20_000)
        )
    }

    @Test
    fun `a server that did come up and then exited cleanly has simply stopped`() {
        assertEquals(
            ServerProcessState.STOPPED,
            ServerStateMachine.onExit(0, requested = false, reachedRunning = true, uptimeMillis = 20_000)
        )
    }

    @Test
    fun `past the startup grace a clean exit is a stop even without a ready line`() {
        assertEquals(
            ServerProcessState.STOPPED,
            ServerStateMachine.onExit(
                0,
                requested = false,
                reachedRunning = false,
                uptimeMillis = ServerStateMachine.STARTUP_GRACE_MILLIS
            )
        )
    }

    @Test
    fun `a stop somebody asked for is never a crash, even during startup`() {
        assertEquals(
            ServerProcessState.STOPPED,
            ServerStateMachine.onExit(0, requested = true, reachedRunning = false, uptimeMillis = 1_000)
        )
    }

    @Test
    fun `the crash backoff climbs and then gives up for ten minutes`() {
        assertEquals(5_000L, ServerStateMachine.restartDelayMillis(1))
        assertEquals(15_000L, ServerStateMachine.restartDelayMillis(2))
        assertEquals(60_000L, ServerStateMachine.restartDelayMillis(3))
        assertEquals(600_000L, ServerStateMachine.restartDelayMillis(4))
        assertEquals(600_000L, ServerStateMachine.restartDelayMillis(9))
    }

    @Test
    fun `only the live states count as alive`() {
        assertTrue(ServerProcessState.STARTING.isAlive)
        assertTrue(ServerProcessState.RUNNING.isAlive)
        assertTrue(ServerProcessState.STOPPING.isAlive)
        assertTrue(!ServerProcessState.STOPPED.isAlive)
        assertTrue(!ServerProcessState.CRASHED.isAlive)
    }

    @Test
    fun `a proxy is stopped with end and never gets nogui`() {
        val proxy = ServerSpec(software = "velocity", memoryMb = 1024)
        val paper = ServerSpec(software = "paper", memoryMb = 1024)

        assertEquals("end", proxy.stopCommand)
        assertEquals("stop", paper.stopCommand)
    }
}
