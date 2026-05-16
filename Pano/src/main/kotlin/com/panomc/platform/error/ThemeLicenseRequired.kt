package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * Returned by [com.panomc.platform.route.api.panel.theme.PanelActivateThemeAPI] when an
 * operator tries to activate a premium theme that fails the license check (no purchase,
 * Pano account not connected, expired token, version/hash mismatch, etc.).
 *
 * The extras map carries `themeId`, `licenseDeniedReason` (stable [com.panomc.platform.license.LicenseDeniedReason.publicId])
 * and an optional human-readable `message`, so the panel UI can show the right call-to-action
 * ("Buy license", "Connect Pano account", …) instead of a generic failure.
 */
class ThemeLicenseRequired(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
