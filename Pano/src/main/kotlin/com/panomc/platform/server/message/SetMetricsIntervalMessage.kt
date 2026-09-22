package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Asks a connected plugin to report its metrics every [intervalMs] (`SET_METRICS_INTERVAL`,
 * §2.4.23 A).
 *
 * Same lease as the node's: re-sent every minute while somebody watches fast, dropped by the plugin
 * after ninety seconds of silence. A plugin too old to know it keeps its ten seconds.
 */
data class SetMetricsIntervalMessage(
    val intervalMs: Long
) : PlatformMessage
