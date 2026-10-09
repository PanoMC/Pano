package com.panomc.platform.error

import com.panomc.platform.model.Error

class CantDeleteAdminPermission(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("CANT_DELETE_ADMIN_PERMISSION", 401, statusMessage, extras)