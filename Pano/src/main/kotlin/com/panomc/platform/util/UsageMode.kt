package com.panomc.platform.util

enum class UsageMode {
    WEBSITE,
    SERVERS,
    BOTH;

    companion object {
        /** Every mode: what an endpoint that is not tied to either half of Pano exists in. */
        val ALL: Set<UsageMode> = entries.toSet()

        /**
         * The modes with a public website. The website's own features -- posts, tickets, themes --
         * exist only in these; a SERVERS install answers 404 for them.
         */
        val WITH_WEBSITE: Set<UsageMode> = setOf(WEBSITE, BOTH)

        /**
         * The modes with server management. Its features -- the servers workspace, nodes, tasks,
         * alerts -- exist only in these; a WEBSITE install answers 404 for them. Linking a server
         * through pano-mc-plugin is not one of them: that is part of the website too.
         */
        val WITH_SERVERS: Set<UsageMode> = setOf(SERVERS, BOTH)
    }
}
