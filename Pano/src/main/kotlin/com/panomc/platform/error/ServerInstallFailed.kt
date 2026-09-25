package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A start or restart was asked of a managed server whose install failed (`server.installError`).
 * Its node never registered it, so there is nothing to start until it is reinstalled; the reason
 * rides along as `installError`. Nothing was sent.
 */
class ServerInstallFailed(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(409, statusMessage, extras)
