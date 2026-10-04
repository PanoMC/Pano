package com.panomc.node.server

import java.util.concurrent.ConcurrentHashMap

/**
 * When each server's vitals are due (SM-58, §2.4.23 A).
 *
 * Every server gets a *full* report every [DEFAULT_INTERVAL_MS]: its process sample, its disk and a
 * server list ping, exactly as before this existed. On top of that Pano can ask for a server's
 * process sample — CPU, memory, traffic — more often while somebody watches it, and between two full
 * reports that server then gets *process* reports at the asked rate. The ping stays at the default
 * cadence: it is a TCP handshake with the server, and the player list does not move every second.
 *
 * The fast rate is a lease. Pano re-sends it every minute while the watcher is still there; after
 * [LEASE_MS] without hearing it the server is back to the default, so a Pano that went away
 * mid-watch cannot leave a node sampling every second forever.
 *
 * Decided on a half-second tick — the fastest rate there is — with half a tick of slack so a rate
 * that is a whole number of ticks lands on them rather than drifting to the tick after.
 */
class MetricsSchedule(private val clock: () -> Long = { System.currentTimeMillis() }) {
    /** What a server gets on this tick. */
    enum class Decision {
        /** Nothing yet. */
        NONE,

        /** Process sample, disk and ping — the default report. */
        FULL,

        /** Process sample only, between two full reports. */
        PROCESS
    }

    private data class Lease(val intervalMs: Long, val until: Long)

    private val leases = ConcurrentHashMap<String, Lease>()

    private val lastFull = ConcurrentHashMap<String, Long>()

    private val lastSample = ConcurrentHashMap<String, Long>()

    /**
     * Pano asked for [uuid]'s process sample every [requestedMs]; renews the lease when asked again.
     *
     * Clamped to [MIN_INTERVAL_MS]…[DEFAULT_INTERVAL_MS]: faster than half a second is not something
     * a panel can show, and slower than the default is the default — the full report comes anyway.
     */
    fun setInterval(uuid: String, requestedMs: Long?) {
        val interval = (requestedMs ?: DEFAULT_INTERVAL_MS).coerceIn(MIN_INTERVAL_MS, DEFAULT_INTERVAL_MS)

        if (interval >= DEFAULT_INTERVAL_MS) {
            leases.remove(uuid)
        } else {
            leases[uuid] = Lease(interval, clock() + LEASE_MS)
        }
    }

    /**
     * How often [uuid]'s process is sampled right now.
     *
     * [docker] servers are never sampled faster than [DOCKER_MIN_INTERVAL_MS]: `docker stats` takes
     * a second or two per call, and asking it more often only queues calls behind each other.
     */
    fun intervalOf(uuid: String, docker: Boolean): Long {
        val lease = leases[uuid] ?: return DEFAULT_INTERVAL_MS

        if (lease.until <= clock()) {
            leases.remove(uuid, lease)

            return DEFAULT_INTERVAL_MS
        }

        return if (docker) maxOf(lease.intervalMs, DOCKER_MIN_INTERVAL_MS) else lease.intervalMs
    }

    /** What [uuid] gets on this tick, recorded as done. */
    fun decide(uuid: String, docker: Boolean): Decision {
        val now = clock()
        val full = lastFull[uuid]

        if (full == null || now - full >= DEFAULT_INTERVAL_MS - SLACK_MS) {
            lastFull[uuid] = now
            lastSample[uuid] = now

            return Decision.FULL
        }

        val interval = intervalOf(uuid, docker)

        if (interval < DEFAULT_INTERVAL_MS && now - (lastSample[uuid] ?: full) >= interval - SLACK_MS) {
            lastSample[uuid] = now

            return Decision.PROCESS
        }

        return Decision.NONE
    }

    /** Drops everything about [uuid], for a server that was deleted. */
    fun forget(uuid: String) {
        leases.remove(uuid)
        lastFull.remove(uuid)
        lastSample.remove(uuid)
    }

    companion object {
        /** The full report's cadence, and the ping's, whatever Pano asks for. */
        const val DEFAULT_INTERVAL_MS = 10_000L

        /** Fastest process sample a server can be asked for. */
        const val MIN_INTERVAL_MS = 500L

        /** Fastest a container is sampled: `docker stats` is slow. */
        const val DOCKER_MIN_INTERVAL_MS = 5_000L

        /** A fast rate without a renewal for this long is back to the default. */
        const val LEASE_MS = 90_000L

        /** How often the schedule is asked: as often as the fastest rate, or it could not be honoured. */
        const val TICK_MS = 500L

        private const val SLACK_MS = TICK_MS / 2
    }
}
