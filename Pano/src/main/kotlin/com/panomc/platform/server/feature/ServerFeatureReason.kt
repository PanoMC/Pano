package com.panomc.platform.server.feature

import com.panomc.platform.server.ServerCapability

/**
 * Why a feature has no source right now (SM-70, §2.4.35).
 *
 * `null` in `features` used to say only "nobody can", and the panel had to guess the rest — which
 * is how a server with an out-of-date plugin ended up telling its admin that "neither the node nor
 * the Pano plugin can do this". The reason is decided here, next to the table it explains, so the
 * notice on the page, the tooltip on a disabled button and the toast after a refused request all
 * say the same thing and name what would fix it.
 *
 * The names are wire values (`features.reasons` and the 409 body's `reason`) the panel switches
 * on, so they must stay stable. They are listed in the order [ServerFeatureResolver.explain]
 * checks them, which is the order in which one explains the next.
 */
enum class ServerFeatureReason {
    /**
     * The software itself has no such thing, whatever else is attached: plugins on Vanilla, a
     * tick counter on a proxy or on a server no Pano plugin exists for, and a plugin toggle
     * outside the Bukkit family with no node to rename the jar instead.
     */
    NOT_SUPPORTED,

    /** A managed server whose node is not connected; nearly everything else follows from that. */
    NODE_OFFLINE,

    /** Only a node could ever do it (start, kill, host metrics) and this server is not on one. */
    NODE_ONLY,

    /** It needs something alive inside the game — the process, or the plugin in it — and it is down. */
    SERVER_STOPPED,

    /**
     * The node restores a backup in place, which it refuses to do under a live process, and no
     * plugin is there to leave a next-start marker instead.
     */
    SERVER_RUNNING,

    /**
     * The node adopted the process across its own restart and holds no stdin for it (§2.4.16),
     * and no plugin can relay the command either.
     */
    NO_STDIN,

    /** Only the Pano plugin could do it here, and it holds no socket right now. */
    PLUGIN_NOT_CONNECTED,

    /** The plugin is connected but predates the capability handshake, so it announces nothing. */
    PLUGIN_OUTDATED,

    /** The plugin is current and connected but does not announce the capability it would need. */
    PLUGIN_LACKS
}

/**
 * One feature's [reason], plus the plugin [capability] that would have served it when the reason
 * is about the plugin — `PLUGIN_NOT_CONNECTED`, `PLUGIN_OUTDATED` and `PLUGIN_LACKS` always carry
 * one, so the 409 can name what to turn on.
 */
data class ServerFeatureUnavailability(
    val reason: ServerFeatureReason,
    val capability: ServerCapability? = null
) {
    /** The extras a refusal carries: `{ feature, reason, capability? }`. */
    fun toErrorExtras(featureId: String): Map<String, Any?> = buildMap {
        put("feature", featureId)
        put("reason", reason.name)

        capability?.let { put("capability", it.id) }
    }
}
