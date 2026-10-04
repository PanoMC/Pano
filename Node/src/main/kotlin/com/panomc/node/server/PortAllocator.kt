package com.panomc.node.server

import com.panomc.node.host.HostPlatform
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Picks the game port for a server Pano left the choice of to the node.
 *
 * Pano cannot make this decision: it does not know what else is already listening on the host, and
 * two installs started seconds apart would otherwise be handed the same number. The node owns a
 * configured range and has three reasons to skip a number in it, all of which have to be checked
 * together: one of its own servers already holds it, a task that has not finished yet has claimed
 * it ([PortReservations]), or something outside Pano entirely is bound to it right now.
 *
 * The third is the only one that can see past this process, and the second is the one that was
 * missing when a concurrent install and import were both handed 25567.
 */
object PortAllocator {
    /**
     * The first port in [range] that is in neither [taken] nor [reserved] and is not bound, or
     * null when the range is exhausted. [isFree] is injected so the rule can be tested without
     * binding sockets.
     */
    fun allocate(
        range: IntRange,
        taken: Set<Int>,
        reserved: Set<Int> = emptySet(),
        isFree: (Int) -> Boolean = ::isPortFree
    ): Int? = range.firstOrNull { it !in taken && it !in reserved && isFree(it) }

    /**
     * Whether a server could bind [port] on this host right now.
     *
     * Probed the way the server itself binds: with `SO_REUSEADDR` on, as the JDK sets it for a
     * listening socket on Unix. Without it Linux refuses a port that still has connections in
     * TIME_WAIT, which is every port a server kicked its players off when it stopped, so a
     * reinstall right after the stop found its own port "in use" and moved the server to the next
     * one. A port something is listening on is refused either way. On Windows `SO_REUSEADDR` lets
     * a bind take a port another socket is listening on, so it stays off there.
     */
    fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = !HostPlatform.isWindows
            socket.bind(InetSocketAddress(port), 1)
        }

        true
    } catch (_: Exception) {
        false
    }

    /**
     * What a server asked to bind [requested] should actually bind.
     *
     * Pano reserves the port on the row and sends the number, which works right up until the host
     * has something on it that Pano cannot see. On a real VPS that is the normal case rather than
     * the exception -- a router, another panel, a container publishing 25565 -- and the server then
     * came up, failed to bind, and exited *cleanly*, so it was reported as a tidy STOPPED while the
     * operator had no server.
     *
     * So the number is probed here, on the machine that can actually answer the question, and a
     * port somebody else holds is swapped for the next free one in the node's range rather than
     * written into `server.properties` and discovered twenty seconds later. The caller reports the
     * swap back to Pano, because a port Pano does not know about is a row that points players at
     * the wrong address.
     *
     * A [requested] outside [range] is moved into it, and reported the same way. The range is what
     * a node in a container publishes, and a server Pano put on 25565 inside a container that
     * publishes 25660-25669 bound, came up and reported healthy while nobody outside could reach
     * it. [allowOutsideRange] is for a port an import found in its own directory rather than one
     * Pano asked for: an adopted server keeps the address its players already know.
     *
     * A [requested] outside 1..65535 is "you pick one" and produces no change to report.
     */
    fun resolve(
        requested: Int,
        range: IntRange,
        taken: Set<Int>,
        reserved: Set<Int> = emptySet(),
        allowOutsideRange: Boolean = false,
        isFree: (Int) -> Boolean = ::isPortFree
    ): Resolution? {
        if (requested !in 1..65535) {
            return allocate(range, taken, reserved, isFree)?.let { Resolution(it) }
        }

        val reason = when {
            !allowOutsideRange && requested !in range ->
                "port $requested is outside this node's port range ${range.first}-${range.last}"
            requested in taken -> "another server on this node already uses port $requested"
            requested in reserved -> "another task on this node is already installing on port $requested"
            !isFree(requested) -> "port $requested is already in use on this host"
            else -> return Resolution(requested)
        }

        val allocated = allocate(range, taken + requested, reserved, isFree) ?: return null

        return Resolution(allocated, requested, reason)
    }

    /**
     * The port a server will bind, and what it was asked to bind when the two differ.
     *
     * [requestedPort] is null when nothing was overridden, so [changed] is the single question the
     * caller has to ask before telling Pano anything.
     */
    data class Resolution(
        val port: Int,
        val requestedPort: Int? = null,
        val reason: String? = null
    ) {
        val changed get() = requestedPort != null && requestedPort != port
    }
}
