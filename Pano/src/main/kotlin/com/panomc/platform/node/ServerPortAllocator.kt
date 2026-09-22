package com.panomc.platform.node

import com.panomc.platform.db.model.Server

/**
 * Picks a managed server's port on the Pano side, when the row is created.
 *
 * Every managed server gets its port from here, written on the row before the node hears about the
 * server at all. That is the fix for a split brain that produced two servers on 25567: Pano
 * allocated from what it could see, the node allocated for a concurrent import from what *it* could
 * see, and neither knew about the other. A row that carries a port from the moment it is created is
 * a row the next allocation can see, and a node that is told the number never picks one of its own.
 *
 * The rule is deliberately dumb and deterministic: walk up from [FIRST_PORT], skip everything the
 * node's own servers already hold, and hand out the next free number. It cannot see ports held by
 * something other than Pano on that host — the node probes before it installs and reports a move
 * with `SERVER_PORT_CHANGED`, which is the honest answer to a guess made from another machine.
 *
 * A node that announced its port range in its hello is allocated inside that range instead (see
 * [NodeManager.getPortRange]). That is not a nicety: a node in a container can only be reached on
 * the ports the container publishes, and a Coolify node publishing 25660-25669 was given 25565 by
 * the walk above — a server that came up healthy and that nobody outside could connect to.
 */
object ServerPortAllocator {
    /** Where the walk starts: Minecraft's own default port. */
    const val FIRST_PORT = 25565

    const val LAST_PORT = 65535

    /**
     * [count] free ports in ascending order, skipping [taken].
     *
     * Returns null when the range runs out, which the caller turns into a refusal instead of a
     * server with no port.
     */
    fun allocate(
        count: Int,
        taken: Set<Int>,
        from: Int = FIRST_PORT,
        to: Int = LAST_PORT
    ): List<Int>? {
        if (count <= 0) {
            return emptyList()
        }

        val start = from.coerceIn(1, LAST_PORT)
        val end = to.coerceIn(start, LAST_PORT)

        val allocated = mutableListOf<Int>()
        // Ports handed out inside this call count as taken for the rest of it, or asking for
        // several at once would hand out the same number several times.
        val used = taken.toMutableSet()

        var port = start

        while (port <= end && allocated.size < count) {
            if (used.add(port)) {
                allocated.add(port)
            }

            port++
        }

        return if (allocated.size == count) allocated else null
    }

    /**
     * [count] free ports inside [nodeRange], the range the node announced in its hello, or walking
     * up from [FIRST_PORT] as always when the node has not announced one (an older daemon).
     *
     * Every allocation for a node goes through here with that node's range, so a port Pano hands
     * out is one the node can actually give its server.
     */
    fun allocate(count: Int, taken: Set<Int>, nodeRange: IntRange?): List<Int>? =
        if (nodeRange == null) {
            allocate(count, taken)
        } else {
            allocate(count, taken, from = nodeRange.first, to = nodeRange.last)
        }

    /** The refusal when [allocate] ran out, naming the node's range when that is what ran out. */
    fun noFreePortMessage(nodeRange: IntRange?): String =
        nodeRange?.let { "No free port is left in this node's port range (${it.first}-${it.last})." }
            ?: "No free port is left on this node."

    /**
     * Every port [servers] already speak for, which is what an allocation has to walk around.
     *
     * Both columns are read, and that is not belt and braces: `gamePort` is what Pano reserved or
     * the node reported, `port` is the address players connect to, and a server that has been
     * around since before managed servers existed has only the second. A row still installing
     * counts exactly like a running one — the whole point is to reserve the number before anything
     * is listening on it.
     */
    fun takenPorts(servers: List<Server>): Set<Int> = servers
        .flatMap { listOfNotNull(it.gamePort, it.port) }
        .filter { it in 1..LAST_PORT }
        .toSet()
}
