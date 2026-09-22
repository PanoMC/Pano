package com.panomc.node.server

import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.PlatformConnection
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonObject

/**
 * Decides the port a server being installed or imported will actually bind, and tells Pano when it
 * is not the one it asked for.
 *
 * Pano reserves the number on the row before the message goes out, which is right: two sides
 * allocating from two different pictures is what once handed two servers 25567. What Pano cannot
 * do is see the host. On a real machine 25565 is very often already taken by something that has
 * nothing to do with Pano — a proxy, a router, another panel — and the server installed on it came
 * up, failed to bind, and then exited with status 0, so the node reported a perfectly clean STOPPED
 * for a server that had never run.
 *
 * So the number is probed here, where the answer exists, and a port somebody else holds is swapped
 * for the next free one in the node's range before `server.properties` is written. The swap is
 * pushed back as `SERVER_PORT_CHANGED` rather than kept quiet: a port only the node knows about is
 * a row that sends players to the wrong address.
 *
 * A number Pano sent that lies outside the node's range is moved into it the same way. The range
 * is what a node in a container publishes, and a port outside it is one nobody can reach.
 *
 * The new port is re-reserved under the same uuid, so a task running alongside this one walks
 * around it exactly as it would have walked around the original.
 *
 * [isFree] and [send] default to the real probe and the real socket; tests replace them.
 */
class PortResolver(
    private val registry: ServerRegistry,
    private val reservations: PortReservations,
    connection: PlatformConnection,
    private val logger: NodeLogger,
    private val isFree: (Int) -> Boolean = PortAllocator::isPortFree,
    private val send: (String, JsonObject) -> Unit = connection::send,
    private val portRange: () -> IntRange
) {
    /**
     * The port [uuid] should bind, or null when the node's range is exhausted.
     *
     * [requested] is what Pano sent (zero or absent means "you pick one"), and [detected] is a port
     * an import found in the directory it just copied — used only when Pano left the choice open,
     * because an imported server whose players know an address should keep it.
     */
    fun resolve(uuid: String, requested: Int?, detected: Int? = null): Int? {
        val fromPano = requested?.takeIf { it in 1..65535 }

        val candidate = fromPano ?: detected?.takeIf { it in 1..65535 } ?: 0

        val resolution = PortAllocator.resolve(
            requested = candidate,
            range = portRange(),
            // Both exclude this server's own claims: a reinstall keeps the port it already had,
            // as long as that port is inside the range.
            taken = registry.takenPorts(uuid),
            reserved = reservations.ports(uuid),
            isFree = isFree,
            // Only Pano's number is held to the range. A port found in an adopted directory is the
            // one its players already use, and is only moved when something else holds it.
            allowOutsideRange = fromPano == null
        ) ?: return null

        if (!resolution.changed) {
            return resolution.port
        }

        // Claimed again under the new number before anything else can walk the range.
        reservations.reserve(uuid, resolution.port)

        logger.warn(
            "Server $uuid was given port ${resolution.requestedPort} but ${resolution.reason}; " +
                "using ${resolution.port} instead."
        )

        send(
            NodeProtocol.Outbound.SERVER_PORT_CHANGED,
            JsonObject()
                .put("serverUuid", uuid)
                .put("requestedPort", resolution.requestedPort)
                .put("port", resolution.port)
                .put("reason", resolution.reason)
        )

        return resolution.port
    }
}
