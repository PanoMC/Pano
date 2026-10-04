package com.panomc.platform.server.feature

/**
 * Who actually performs one feature for one server (SM-52, §2.4.17).
 *
 * The panel never asks "is this server managed?" or "does its plugin have capability X?" again: it
 * asks which source will do the job, and `null` means nobody can right now. The ids are wire
 * values the panel switches on, so they must stay stable.
 */
enum class ServerFeatureSource(val id: String) {
    /** The node daemon that owns the process. */
    NODE("node"),

    /** The Pano plugin running inside the game. */
    PLUGIN("plugin"),

    /** Pano itself, from state it already holds (the console ring buffer, its own cron clock). */
    PANO("pano");

    companion object {
        /** The first source in [candidates] that is actually there, or `null` when none is. */
        fun firstOf(vararg candidates: Pair<ServerFeatureSource, Boolean>): ServerFeatureSource? =
            candidates.firstOrNull { it.second }?.first
    }
}
