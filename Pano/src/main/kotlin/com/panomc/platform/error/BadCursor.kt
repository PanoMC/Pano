package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A console search cursor the source cannot resume from: malformed, from another version, or
 * naming a log file that has since rotated away.
 *
 * Distinct on purpose — it is the one refusal the panel answers by starting the search over from
 * the newest file, where every other error ends the search.
 */
class BadCursor(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
