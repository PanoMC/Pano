package com.panomc.platform.license

/**
 * Snapshot of a license-related failure for a premium theme, captured by [LicenseManager].
 *
 * Surfaced via [LicenseManager.getThemeFailures] so the panel UI can show a remediation
 * (e.g. "Buy License", "Connect panomc.com account", "Retry network"). When this is set for
 * the currently active theme the host force-falls back to the bundled vanilla theme so a
 * server never serves a premium theme without a valid license.
 */
data class ThemeLicenseFailure(
    val themeId: String,
    val version: String?,
    val reason: LicenseDeniedReason,
    val message: String?,
    val recordedAtMs: Long = System.currentTimeMillis()
)
