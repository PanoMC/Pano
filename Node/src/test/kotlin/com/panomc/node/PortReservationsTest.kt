package com.panomc.node

import com.panomc.node.server.PortAllocator
import com.panomc.node.server.PortReservations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The collision this prevents really happened: an install and an import running at the same time
 * were both handed 25567, because neither was registered yet when the other looked.
 */
class PortReservationsTest {
    @Test
    fun `a claimed port is gone for everyone but its own server`() {
        val reservations = PortReservations()

        reservations.reserve("install", 25567)

        assertEquals(setOf(25567), reservations.ports("import"))
        assertTrue(reservations.ports("install").isEmpty())
    }

    @Test
    fun `a task that finishes or fails leaves nothing behind`() {
        val reservations = PortReservations()

        reservations.reserve("install", 25567)
        reservations.release("install")

        assertEquals(0, reservations.size())
        assertTrue(reservations.ports().isEmpty())
    }

    @Test
    fun `a retry replaces its own claim rather than leaking one`() {
        val reservations = PortReservations()

        reservations.reserve("install", 25567)
        reservations.reserve("install", 25570)

        assertEquals(setOf(25570), reservations.ports("other"))
        assertEquals(1, reservations.size())
    }

    @Test
    fun `an install that left the choice to the node claims nothing`() {
        val reservations = PortReservations()

        reservations.reserve("install", 0)
        reservations.reserve("other", null)

        assertTrue(reservations.ports().isEmpty())
    }

    @Test
    fun `an allocation walks around a port a task in flight already holds`() {
        val reservations = PortReservations()

        reservations.reserve("install", 25565)

        // Nothing is registered and nothing is bound: the claim is the only thing that knows.
        assertEquals(
            25566,
            PortAllocator.allocate(25565..25570, emptySet(), reservations.ports("import")) { true }
        )
    }

    @Test
    fun `every reason a port is unavailable is honoured at once`() {
        val reservations = PortReservations()

        reservations.reserve("install", 25566)

        assertEquals(
            25568,
            PortAllocator.allocate(25565..25570, setOf(25565), reservations.ports()) { it != 25567 }
        )

        assertNull(PortAllocator.allocate(25565..25566, setOf(25565), setOf(25566)) { true })
    }
}
