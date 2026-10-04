package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The source could not read the server's log files for a console search.
 *
 * An error rather than an empty page, because an empty page says "this never happened", which is
 * exactly what nobody should be told because a disk misbehaved.
 */
class ReadFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(500, statusMessage, extras)
