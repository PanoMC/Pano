package com.panomc.platform.license

import com.panomc.platform.util.TextUtil

/**
 * Walks [cause] chains (PF4J sometimes wraps plugin exceptions).
 */
fun Throwable?.findLicenseRequiredInCauseChain(maxDepth: Int = 16): LicenseRequiredException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < maxDepth) {
        if (current is LicenseRequiredException) return current
        current = current.cause
        depth++
    }
    return null
}

/**
 * JSON for panel lists/detail: omit stack dumps when the plugin intentionally aborted startup for DRM.
 * Details belong in `licenseStatus` / `licenseFailureMessage`.
 */
fun Throwable?.panelPluginStartupErrorText(): String? {
    if (this == null) return null
    if (findLicenseRequiredInCauseChain() != null) return null
    return TextUtil.getStackTraceAsString(this)
}

fun Throwable?.isPluginStartupBlockedByLicense(): Boolean =
    this != null && findLicenseRequiredInCauseChain() != null
