package com.panomc.platform.server

/**
 * Server software Pano knows about.
 *
 * A linked server announces its own type on connect; a managed server gets the type it was
 * installed with. Names are part of the wire protocol (the plugin sends them verbatim), so they
 * must stay stable. Members added for managed servers may never appear on a linked server —
 * VANILLA has no plugin API at all — which is exactly why the family checks below are properties
 * instead of assumptions spread over the call sites.
 */
enum class ServerType {
    BUNGEECORD,
    FABRIC,
    FOLIA,
    PAPER,
    SPIGOT,
    BUKKIT,
    VELOCITY,
    VANILLA,
    PURPUR,
    WATERFALL,
    QUILT,
    FORGE,
    NEOFORGE;

    /**
     * Servers that implement the Bukkit API, and therefore share its plugin manager and its
     * command set. The only family where Pano can enable or disable a plugin at runtime.
     */
    val isBukkitFamily get() = this == SPIGOT || this == BUKKIT || this == PAPER || this == FOLIA ||
            this == PURPUR

    /** Proxies, which run no world: no gamemode, no op, no player inventory. */
    val isProxy get() = this == VELOCITY || this == BUNGEECORD || this == WATERFALL

    /**
     * Servers that run no plugin platform Pano has a mc-plugin module for, so everything about
     * them comes from the node: console from stdout, power from the process, and nothing else.
     */
    val hasNoPluginModule get() = this == VANILLA || this == FORGE || this == NEOFORGE
}
