package com.panomc.platform.error

import com.panomc.platform.model.Error

class SomeUsersArentExists(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("SOME_USERS_ARENT_EXISTS", 404, statusMessage, extras)