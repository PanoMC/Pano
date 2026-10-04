package com.panomc.platform.server

import java.time.ZoneId

/**
 * A server's own time zone, for the Overview's "Server time" switch (SM-60, §2.4.25).
 *
 * Two peers can say what it is: the node, for every server on its host, and the plugin, for the
 * game JVM it runs in — which is the better answer (a container is often UTC on a host that is not),
 * so it wins. Whatever either sends is checked against the IANA zone ids the JDK knows, because the
 * panel hands it straight to `Intl.DateTimeFormat({ timeZone })`, which knows the same list and
 * throws on anything else: a raw offset, `GMT+03:00`, a made-up region — all dropped.
 */
object ServerTimeZone {
    /** Longest zone id stored; the column is `varchar(64)` and no real id comes close. */
    const val MAX_LENGTH = 64

    /** [raw] as a zone id the panel can use, or null when it is not one. */
    fun normalise(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH && it in ZoneId.getAvailableZoneIds() }

    /**
     * The zone a plugin's connect leaves on the row: the one it reported when that is valid, the
     * stored one otherwise — an older plugin that sends none must not erase what the node said.
     */
    fun fromPlugin(current: String?, reported: String?): String? = normalise(reported) ?: current

    /**
     * The zone a node's hello should write for one of its servers, or null to leave the row alone.
     *
     * The node fills a gap and speaks for servers no plugin has ever connected from ([pluginVersion]
     * null); once a plugin has, the zone is the plugin's business and the node only fills it while it
     * is still empty.
     */
    fun fromNode(current: String?, pluginVersion: String?, reported: String?): String? {
        val zone = normalise(reported) ?: return null

        if (current != null && pluginVersion != null) {
            return null
        }

        return zone.takeIf { it != current }
    }
}
