package com.panomc.platform.server

/**
 * Where a newly created managed server's files come from.
 *
 * Part of the `servers/create` contract rather than an internal detail: the wizard's first step
 * is exactly this choice, and it decides which of the body's other fields are required and
 * whether `software`/`version` are answers or guesses.
 */
enum class ServerCreateSource {
    /** Pano downloads the software somebody picked. */
    FRESH,

    /** A directory already sitting on the node's host. */
    EXISTING_FOLDER,

    /** A zip the browser uploaded through `POST /api/panel/transfers/upload`. */
    UPLOAD,

    /** A Modrinth `.mrpack`. */
    MODPACK,

    /**
     * A directory already on the node's host, run from where it is rather than copied: the "link
     * an existing server with the Pano Agent" choice. Removing the server later keeps its files.
     */
    IN_PLACE;

    companion object {
        /** [id] as a source; a missing one is [FRESH], and anything unknown is null. */
        fun fromId(id: String?): ServerCreateSource? {
            if (id.isNullOrBlank()) {
                return FRESH
            }

            return entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }
        }
    }
}
