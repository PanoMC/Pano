package com.panomc.platform.server.dto

/**
 * One bucket of a server's player history, as the Server Activity chart draws it (§2.4.19,
 * §2.4.26): a minute, an hour, a local day or — rolled up — a month.
 *
 * [peak] is the highest per-minute reading in the bucket and [average] the mean over the minutes
 * that were actually recorded — not over every minute of it, because a server that was off for
 * twenty hours of a day did not have zero players for twenty hours, it had none worth counting.
 */
data class PlayerActivity(val peak: Long, val average: Double)
