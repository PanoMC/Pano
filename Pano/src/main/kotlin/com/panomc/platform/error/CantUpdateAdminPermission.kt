package com.panomc.platform.error

import com.panomc.platform.model.Error

class CantUpdateAdminPermission(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CANT_UPDATE_ADMIN_PERMISSION", 401, statusMessage, extras)