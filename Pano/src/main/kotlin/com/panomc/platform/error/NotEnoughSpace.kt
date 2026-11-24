package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The disk that would receive this file has too little free space left.
 *
 * Carries `required` and `free` (bytes) in its extras when they are known, so the panel can say
 * how much is missing instead of only that something is.
 */
class NotEnoughSpace(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NOT_ENOUGH_SPACE", 409, statusMessage, extras)
