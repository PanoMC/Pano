package com.panomc.platform.server

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The half-finished requests Pano has out to Minecraft servers.
 *
 * A plugin socket is one multiplexed pipe: several panels can be reading the same server at once,
 * and the answers come back whenever the server's main thread gets to them. The `eventId` is what
 * turns that back into so many answers to so many questions, and this is the map that holds them.
 *
 * Kept separate from [ServerManager] so the correlation can be exercised without a socket — the
 * failure modes that matter here (a reply for a request nobody is waiting on, a server that goes
 * away with requests in flight, a second reply for the same id) are exactly the ones that are
 * impossible to provoke on purpose in a running system.
 */
class ServerRequestRegistry {
    private data class Pending(val serverId: Long, val result: CompletableDeferred<ServerEventRequest>)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Registers a new request for [serverId] and returns its id and the answer to await. */
    fun register(serverId: Long): Pair<String, CompletableDeferred<ServerEventRequest>> {
        val eventId = UUID.randomUUID().toString()
        val result = CompletableDeferred<ServerEventRequest>()

        pending[eventId] = Pending(serverId, result)

        return eventId to result
    }

    /**
     * Delivers a reply, and reports whether anything was waiting for it.
     *
     * [serverId] is checked, not trusted: a server must not be able to answer another server's
     * request by guessing an id, which would let it put its own content into a panel reading
     * somebody else's console.
     */
    fun complete(serverId: Long, eventId: String?, reply: ServerEventRequest): Boolean {
        val id = eventId ?: return false
        val entry = pending[id] ?: return false

        if (entry.serverId != serverId) {
            return false
        }

        pending.remove(id, entry)

        return entry.result.complete(reply)
    }

    /** Drops a request that will never be answered, after a timeout or a failed send. */
    fun forget(eventId: String) {
        pending.remove(eventId)
    }

    /**
     * Fails everything outstanding for a server that just disconnected.
     *
     * Without this every panel request in flight would sit out its full timeout after a server
     * restart, which reads to the person using it as Pano having hung.
     */
    fun failAll(serverId: Long, reason: Throwable) {
        pending.entries
            .filter { it.value.serverId == serverId }
            .forEach { entry ->
                pending.remove(entry.key, entry.value)

                entry.value.result.completeExceptionally(reason)
            }
    }

    /** How many requests are outstanding. Exposed for tests and for leak checks. */
    fun size() = pending.size
}
