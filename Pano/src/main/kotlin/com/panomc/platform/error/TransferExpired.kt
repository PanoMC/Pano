package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The transfer's ticket ran out before the file moved.
 *
 * Its own error rather than a generic failure because the answer is different: a transfer that
 * expired can simply be started again, while one that failed usually cannot until something is
 * fixed on the node.
 */
class TransferExpired(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
