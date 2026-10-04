package com.panomc.platform.server

/**
 * Optional features a connected server can announce on connect.
 *
 * The plugin sends the [id]s it supports in the `ON_SERVER_CONNECT` payload and they are stored on
 * the server row, so the panel can hide the features a server cannot serve. Ids are part of the
 * wire protocol and must stay stable; ids Pano does not know are ignored (see [fromId]) instead of
 * failing the handshake, because a newer plugin may announce capabilities an older Pano has never
 * heard of.
 */
enum class ServerCapability(val id: String) {
    /** Streams server log lines to Pano. */
    CONSOLE("console"),

    /** Executes console commands sent by Pano. */
    COMMANDS("commands"),

    /** Can stop or restart the server on request. */
    POWER("power"),

    /** Reports TPS, MSPT, memory usage and the player roster. */
    METRICS("metrics"),

    /** Exposes the online player roster with UUIDs and supports player actions. */
    PLAYERS("players"),

    /** Lists the plugins or mods installed on the server. */
    PLUGINS("plugins"),

    /**
     * Reads and writes the server's own directory (SM-47 agent-lite, §2.4.17 C).
     *
     * The plugin answers the node's `FILE_*` and `TRANSFER_*` shapes verbatim, rooted at whatever
     * the server directory is on that platform, so a server with no node is not a read-only one.
     */
    FILES("files"),

    /** Creates, lists, deletes and restores backups from inside the running server. */
    BACKUPS("backups"),

    /** Downloads a plugin or mod jar into the server, and hashes jars for identification. */
    PLUGIN_INSTALL("plugin-install"),

    /** Runs Pano's schedules on its own clock, and reports each run back. */
    SCHEDULES("schedules"),

    /**
     * Replaces its own jar with a newer build Pano sends (`PANO_PLUGIN_UPDATE`).
     *
     * Announced only by a plugin that could work out which jar it was loaded from, since that is
     * the file it would replace; a plugin that cannot is updated through its node or by hand.
     */
    SELF_UPDATE("self-update");

    companion object {
        /**
         * Resolves a wire id to a known capability, or `null` when this Pano version does not know
         * it. Never throws: unknown ids are dropped on purpose.
         */
        fun fromId(id: String): ServerCapability? = entries.firstOrNull { it.id == id }
    }
}
