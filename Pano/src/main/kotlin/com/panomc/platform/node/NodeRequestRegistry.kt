package com.panomc.platform.node

import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The half-finished requests Pano has out to nodes.
 *
 * A node's socket is one multiplexed pipe: ten panels can be reading ten directories on the same
 * node at once, and the replies come back in whatever order the daemon's worker threads finish.
 * The `eventId` is what turns that back into ten answers to ten questions, and this is the map
 * that holds them.
 *
 * Kept separate from [NodeManager] so the correlation can be exercised without a socket — the
 * failure modes that matter here (a reply for a request nobody is waiting on, a node that goes
 * away with requests in flight, a second reply for the same id) are exactly the ones that are
 * impossible to provoke on purpose in a running system.
 */
class NodeRequestRegistry {
    private data class Pending(val nodeId: Long, val result: CompletableDeferred<JsonObject>)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Registers a new request for [nodeId] and returns its id and the answer to await. */
    fun register(nodeId: Long): Pair<String, CompletableDeferred<JsonObject>> {
        val eventId = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()

        pending[eventId] = Pending(nodeId, result)

        return eventId to result
    }

    /**
     * Delivers a reply, and reports whether anything was waiting for it.
     *
     * [nodeId] is checked, not trusted: a node must not be able to answer another node's request
     * by guessing an id, which would let it put its own content into a panel reading somebody
     * else's server.
     */
    fun complete(nodeId: Long, eventId: String?, payload: JsonObject): Boolean {
        val id = eventId ?: return false
        val entry = pending[id] ?: return false

        if (entry.nodeId != nodeId) {
            return false
        }

        pending.remove(id, entry)

        return entry.result.complete(payload)
    }

    /** Drops a request that will never be answered, after a timeout or a failed send. */
    fun forget(eventId: String) {
        pending.remove(eventId)
    }

    /**
     * Fails everything outstanding for a node that just disconnected.
     *
     * Without this every panel request in flight would sit out its full timeout after a node
     * restart, which reads to the person using it as Pano having hung.
     */
    fun failAll(nodeId: Long, reason: Throwable) {
        pending.entries
            .filter { it.value.nodeId == nodeId }
            .forEach { entry ->
                pending.remove(entry.key, entry.value)

                entry.value.result.completeExceptionally(reason)
            }
    }

    /** How many requests are outstanding. Exposed for tests and for leak checks. */
    fun size() = pending.size
}
