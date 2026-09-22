package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A console search with nothing to search for: the query was blank once trimmed.
 *
 * Its own code rather than a plain [BadRequest] so the panel can tell "type something" apart from
 * every other refusal without reading a message.
 */
class BadQuery(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
