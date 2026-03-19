package com.panomc.platform.error

import com.panomc.platform.model.Error

class PluginDeniedLogin(
    statusMessage: String = "Login denied by plugin",
    extras: Map<String, Any?> = mapOf()
) : Error(403, statusMessage, extras)
