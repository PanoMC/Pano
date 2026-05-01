package com.panomc.platform.license

/**
 * Thrown by premium plugins (and by [LicenseManager.requireLicense] on fetch/parse errors).
 *
 * The exception escapes plugin `onStart` so PF4J marks the plugin FAILED. The host records
 * the failure for the panel; Pano itself keeps running.
 */
class LicenseRequiredException(
    val pluginId: String,
    val reason: LicenseDeniedReason,
    message: String? = null,
    cause: Throwable? = null
) : RuntimeException(buildMessage(pluginId, reason, message), cause) {
    companion object {
        private fun buildMessage(pluginId: String, reason: LicenseDeniedReason, detail: String?): String =
            buildString {
                append("Plugin '").append(pluginId).append("' license check failed: ")
                append(reason.publicId)
                if (!detail.isNullOrBlank()) {
                    append(" (").append(detail).append(')')
                }
            }
    }
}
