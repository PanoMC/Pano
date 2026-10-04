package com.panomc.platform.server

/**
 * How Pano relates to a server's process.
 *
 * [LINKED] is the original model: the server runs wherever its owner started it and only the
 * Pano plugin inside it talks to Pano, so there is no process to start, no files to read and no
 * exit code to report. [MANAGED] means a node daemon owns the process on Pano's behalf, which is
 * what unlocks START/KILL, installs, startup settings and crash reporting.
 *
 * Every server row created before managed servers existed is [LINKED], which is why that is the
 * column default.
 */
enum class ServerKind {
    LINKED,
    MANAGED;

    companion object {
        /** Resolves a wire/stored value, falling back to [LINKED] for anything unknown. */
        fun fromId(id: String?): ServerKind = entries.firstOrNull { it.name == id?.uppercase() } ?: LINKED
    }
}
