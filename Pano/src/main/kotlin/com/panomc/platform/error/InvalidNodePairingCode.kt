package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The pairing code or bootstrap token a node presented is not one Pano is accepting.
 *
 * Deliberately one error for both cases and with no detail: telling an unauthenticated caller
 * which half of the check failed, or how long a code has left, only helps someone guessing.
 */
class InvalidNodePairingCode(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
