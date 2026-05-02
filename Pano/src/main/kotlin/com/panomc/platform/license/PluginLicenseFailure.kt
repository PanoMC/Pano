package com.panomc.platform.license

/**
 * Snapshot of a license-related failure for a plugin id, captured by [LicenseManager].
 *
 * Surfaced via [LicenseManager.getFailures] so the panel UI can show a remediation.
 */
data class PluginLicenseFailure(
    val pluginId: String,
    val resourceId: String?,
    val version: String?,
    val reason: LicenseDeniedReason,
    val message: String?,
    val recordedAtMs: Long = System.currentTimeMillis()
)
