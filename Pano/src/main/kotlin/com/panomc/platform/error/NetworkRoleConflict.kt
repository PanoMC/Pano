package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A server that is part of a proxy network was asked to change between a proxy and a backend
 * software (SM-66, §2.4.31); it has to leave the network first. Nothing was touched.
 */
class NetworkRoleConflict(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
