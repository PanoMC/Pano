package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * The panel's own sign-in form (`POST /api/v1/auth/login` with `panel: true`) was used by an account
 * that has no `pano.panel.access.panel`. No session is created.
 */
class NoPanelAccess(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("NO_PANEL_ACCESS", 403, statusMessage, extras)
