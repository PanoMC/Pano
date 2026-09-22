package com.panomc.platform.server.metrics

/**
 * Which servers Pano has asked to report their vitals faster than usual, and what to tell them next
 * (SM-58, §2.4.23 A).
 *
 * Sources report every [DEFAULT_INTERVAL_MS] on their own. A panel that refreshes every second
 * needs a faster feed, but only while somebody is actually watching — so the rate a server is asked
 * for is the fastest one any of its metrics subscribers wants, and nothing at all once they leave.
 *
 * Never slower than the default: a watcher who picks "every minute" is refreshing the page slowly,
 * not asking the server to report less often, and the per-minute history, the alerts and every other
 * panel on the same server all live off the default feed. So only rates below the default are ever
 * commanded, and "back to normal" is the default itself.
 *
 * Pure bookkeeping, no sockets: the hub feeds it what its sessions want and sends what comes back.
 */
class MetricsRates {
    /** What each fast server was last told, which is what the lease re-sends. */
    private val told = HashMap<Long, Long>()

    /**
     * Brings the commanded rates in line with [wanted] (server id → fastest interval any watcher
     * asked for) and returns the commands to send now, server id → interval.
     *
     * Only what changed goes out: a new fast server, a server whose fastest watcher changed, and —
     * with [DEFAULT_INTERVAL_MS] — a server whose last fast watcher left, so it slows down at once
     * instead of waiting out its lease.
     */
    @Synchronized
    fun reconcile(wanted: Map<Long, Long>): Map<Long, Long> {
        val fast = wanted.mapValues { effective(listOf(it.value)) }.filterValues { it < DEFAULT_INTERVAL_MS }

        val commands = LinkedHashMap<Long, Long>()

        fast.forEach { (serverId, interval) ->
            if (told[serverId] != interval) {
                commands[serverId] = interval
                told[serverId] = interval
            }
        }

        told.keys.filter { it !in fast }.forEach { serverId ->
            commands[serverId] = DEFAULT_INTERVAL_MS
            told.remove(serverId)
        }

        return commands
    }

    /**
     * The commands to re-send so a fast rate outlives the source's lease: every server currently
     * told to report faster than the default. A source that hears nothing for [LEASE_MS] falls back
     * to the default on its own, which is what makes a Pano that died mid-watch harmless.
     */
    @Synchronized
    fun renewals(): Map<Long, Long> = LinkedHashMap(told)

    /**
     * Forgets what [id] was told, so the next [reconcile] tells it again. For a source that just
     * (re)connected: whatever it was told before is gone with its previous process or socket.
     */
    @Synchronized
    fun forget(id: Long) {
        told.remove(id)
    }

    companion object {
        /** The rate every source reports at when nobody asked for anything. */
        const val DEFAULT_INTERVAL_MS = 10_000L

        /** Fastest a panel may ask for: the half-second option. */
        const val MIN_INTERVAL_MS = 500L

        /** Slowest a panel may ask for; it only ever slows the panel, never the source. */
        const val MAX_INTERVAL_MS = 60_000L

        /** How often a fast rate is re-sent. */
        const val RENEW_MS = 60_000L

        /** How long a source keeps a fast rate without hearing it again (enforced by the source). */
        const val LEASE_MS = 90_000L

        /**
         * A subscribe frame's `metricsIntervalMs` as an interval: clamped to the allowed range, the
         * default when missing or not a number.
         */
        fun clamp(requested: Any?): Long {
            val number = (requested as? Number)?.toDouble() ?: return DEFAULT_INTERVAL_MS

            if (!number.isFinite()) {
                return DEFAULT_INTERVAL_MS
            }

            return number.toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        }

        /** Most servers one subscribe frame's `subscribeMetricsServerIds` may name. */
        const val MAX_SUBSCRIBED_SERVERS = 100

        /**
         * A subscribe frame's `subscribeMetricsServerIds` as server ids: the numbers in it, each
         * once, in order, at most [MAX_SUBSCRIBED_SERVERS] of them. Anything that is not an array
         * is no servers at all.
         */
        fun serverIds(requested: Any?): List<Long> {
            val values = when (requested) {
                is Iterable<*> -> requested.toList()
                is Array<*> -> requested.toList()
                else -> return emptyList()
            }

            return values.asSequence()
                .mapNotNull { (it as? Number)?.toLong() }
                .distinct()
                .take(MAX_SUBSCRIBED_SERVERS)
                .toList()
        }

        /**
         * What each server should be asked for, from every watcher's servers and interval: a
         * single-server watcher and a many-server watcher are the same thing here, one interval
         * over a set of servers, and the fastest interval on a server wins.
         */
        fun wanted(watchers: Iterable<Pair<Collection<Long>, Long>>): Map<Long, Long> {
            val wanted = HashMap<Long, Long>()

            watchers.forEach { (serverIds, intervalMs) ->
                serverIds.forEach { serverId -> wanted.merge(serverId, intervalMs) { a, b -> minOf(a, b) } }
            }

            return wanted
        }

        /** The rate a server should report at for these watchers: the fastest, never slower than the default. */
        fun effective(intervals: Collection<Long>): Long =
            (intervals.minOrNull() ?: DEFAULT_INTERVAL_MS).coerceAtMost(DEFAULT_INTERVAL_MS)
    }
}
