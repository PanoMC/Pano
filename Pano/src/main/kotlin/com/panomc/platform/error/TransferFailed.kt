package com.panomc.platform.error

import com.panomc.platform.model.Error

/** A file transfer between the browser and a server broke off before it finished. */
class TransferFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("TRANSFER_FAILED", 409, statusMessage, extras)
