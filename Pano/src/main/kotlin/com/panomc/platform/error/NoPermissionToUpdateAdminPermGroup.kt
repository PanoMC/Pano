package com.panomc.platform.error

import com.panomc.platform.model.Error

class NoPermissionToUpdateAdminPermGroup(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NO_PERMISSION_TO_UPDATE_ADMIN_PERM_GROUP", 403, statusMessage, extras)