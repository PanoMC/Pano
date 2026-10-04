package com.panomc.platform.server.alert

/**
 * The numbers that decide whether a metric is a problem, and the little bit of memory it takes to
 * tell a problem from a blip.
 *
 * Kept pure and apart from [AlertManager] because these are the parts worth being sure about: a
 * threshold that is one comparison in the wrong direction produces either silence or a flood, and
 * neither is discovered quickly in production.
 */
object AlertThresholds {
    /** Above this share of the data disk in use, a node is close to not being able to work. */
    const val DISK_RATIO = 0.9

    /** Below this tick rate a server is not really playable. */
    const val TPS_FLOOR = 15.0

    /** Consecutive samples below the floor before it counts. Ten seconds apart, so just under a minute. */
    const val TPS_SAMPLES = 5

    /**
     * Whether a node's disk is full enough to say something.
     *
     * A total of zero means the node could not measure it, which is not the same as a full disk
     * and must not be reported as one.
     */
    fun isDiskLow(used: Long, total: Long): Boolean {
        if (total <= 0 || used < 0) {
            return false
        }

        return used.toDouble() / total.toDouble() > DISK_RATIO
    }

    /** The share of the disk in use, 0..1, or 0 when it could not be measured. */
    fun diskRatio(used: Long, total: Long): Double {
        if (total <= 0 || used < 0) {
            return 0.0
        }

        return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }
}

/**
 * Counts how long a server has been below the tick-rate floor.
 *
 * A single bad sample is a chunk being generated, not an incident, so the alert needs a run of
 * them. The counter is per server and resets the moment a healthy sample arrives, which is what
 * makes "five in a row" mean a minute of genuinely bad performance rather than five bad seconds
 * spread over an hour.
 *
 * A null reading is not a healthy one: a proxy reports no TPS at all, and treating that as good
 * news would reset the counter of a server that never reports any.
 */
class TpsLowWindow(
    private val floor: Double = AlertThresholds.TPS_FLOOR,
    private val samples: Int = AlertThresholds.TPS_SAMPLES
) {
    private val counters = mutableMapOf<Long, Int>()

    /**
     * Records one reading and reports whether this is the sample that crosses the line.
     *
     * True exactly once per episode: the counter keeps climbing while the server stays bad, but
     * only the sample that reaches the threshold returns true, so the cooldown is not the only
     * thing standing between one bad night and a hundred notifications.
     */
    fun offer(serverId: Long, tps: Double?): Boolean {
        if (tps == null) {
            return false
        }

        if (tps >= floor) {
            counters.remove(serverId)

            return false
        }

        val count = (counters[serverId] ?: 0) + 1

        counters[serverId] = count

        return count == samples
    }

    /** Forgets a server, for when it disconnects or is deleted. */
    fun forget(serverId: Long) {
        counters.remove(serverId)
    }

    /** How many consecutive bad samples this server has had. For tests and diagnostics. */
    fun countFor(serverId: Long): Int = counters[serverId] ?: 0
}

/**
 * Remembers when each alert was last raised, so a condition that keeps being true is not said
 * twice in a row.
 *
 * Keyed by kind *and* subject: two nodes running out of disk are two problems and both deserve
 * saying, while one node saying so every ten seconds is one problem said sixty times.
 */
class AlertCooldownTracker {
    private val lastRaised = mutableMapOf<String, Long>()

    /**
     * Keys this process already knows the history of: raised, cleared, or looked up in the stored
     * alerts. Only a key in none of those asks the table (SM-69, §2.4.34).
     */
    private val known = mutableSetOf<String>()

    /**
     * Whether this alert may be raised now, recording it when it may.
     *
     * The check and the record are one operation on purpose: doing them separately is how two
     * metric frames arriving together both pass the check.
     */
    fun tryRaise(kind: ServerAlertKind, subject: String, now: Long): Boolean {
        val key = keyOf(kind, subject)

        val previous = lastRaised[key]

        known += key

        if (previous != null && now - previous < kind.cooldownMs) {
            return false
        }

        lastRaised[key] = now

        return true
    }

    /**
     * [tryRaise], but asks [storedLastRaise] first when this process has no history for the key.
     *
     * This map is memory, and memory is wiped by every restart: a daily alert raised at nine and a
     * Pano restarted at ten used to say the same thing again at a quarter past, five times on an
     * evening of restarts. The stored `server_alert` rows survive, so the first raise of a key
     * after boot takes the newest one's time as the last raise. After that the map is the fast
     * path and the table is not asked again. A key that was cleared in this process is not asked
     * either -- clearing says the condition went away, and the stored row predates that.
     */
    suspend fun tryRaise(
        kind: ServerAlertKind,
        subject: String,
        now: Long,
        storedLastRaise: suspend () -> Long?
    ): Boolean {
        val key = keyOf(kind, subject)

        if (key !in known) {
            val stored = storedLastRaise()

            known += key

            // A raise that happened while the lookup was suspended is newer than anything stored.
            if (stored != null && stored > (lastRaised[key] ?: Long.MIN_VALUE)) {
                lastRaised[key] = stored
            }
        }

        return tryRaise(kind, subject, now)
    }

    /** Clears the cooldown for one subject, so a condition that returns is reported at once. */
    fun clear(kind: ServerAlertKind, subject: String) {
        val key = keyOf(kind, subject)

        lastRaised.remove(key)

        known += key
    }

    /** Drops everything about a subject, for when the server or node it names is gone. */
    fun forget(subject: String) {
        lastRaised.keys.removeIf { it.endsWith(":$subject") }
        known.removeIf { it.endsWith(":$subject") }
    }

    private fun keyOf(kind: ServerAlertKind, subject: String) = "${kind.name}:$subject"
}
