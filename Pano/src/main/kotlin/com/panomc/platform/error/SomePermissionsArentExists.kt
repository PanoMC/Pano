package com.panomc.platform.error

import com.panomc.platform.model.Error

class SomePermissionsArentExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("SOME_PERMISSIONS_ARENT_EXISTS", 404, statusMessage, extras)