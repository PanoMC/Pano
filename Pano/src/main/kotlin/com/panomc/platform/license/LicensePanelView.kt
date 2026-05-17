package com.panomc.platform.license

import com.panomc.platform.config.ConfigManager

/**
 * Builds the license-related fields exposed to the panel UI for a single plugin.
 *
 * The host has no first-class concept of "premium" — that information lives entirely
 * inside the plugin (which decides for itself whether to call [LicenseManager.requireLicense]).
 * Here we infer status from runtime state:
 *
 * - if the plugin has a recorded failure               -> failure wins (mapped to status)
 * - else if the plugin has a cached, unexpired token   -> LICENSED (panel UX)
 * - else if the host has seen DRM for this plugin ([LicenseManager.hasSeenLicenseRequirement])
 *      and Pano is not connected                       -> NOT_CONNECTED
 * - else if seen DRM and Pano connected but no JWT yet -> NEEDS_REFRESH
 * - otherwise                                          -> NOT_PREMIUM
 *
 * `premium` is true when the host has seen DRM for this plugin this session (including after
 * disconnect clears JWT cache), or there is cache/failure data.
 */
object LicensePanelView {
    fun buildLicenseFields(
        pluginId: String,
        licenseManager: LicenseManager?,
        configManager: ConfigManager,
        isPanoConnected: Boolean
    ): Map<String, Any?> {
        val cached = licenseManager?.getCachedLicense(pluginId)
        val failure = licenseManager?.getFailures()?.firstOrNull { it.pluginId == pluginId }
        val seenDrm = licenseManager?.hasSeenLicenseRequirement(pluginId) == true

        val status = when {
            failure != null -> {
                val fromFailure = failure.reason.toLicenseStatus()
                if (fromFailure == LicenseStatus.NOT_CONNECTED && isPanoConnected) {
                    LicenseStatus.NEEDS_REFRESH
                } else {
                    fromFailure
                }
            }
            cached != null && !cached.claims.isExpired() -> LicenseStatus.LICENSED
            seenDrm && !isPanoConnected -> LicenseStatus.NOT_CONNECTED
            seenDrm && isPanoConnected -> LicenseStatus.NEEDS_REFRESH
            else -> LicenseStatus.NOT_PREMIUM
        }

        val premium = cached != null || failure != null || seenDrm
        val licensed = failure == null && cached != null && !cached.claims.isExpired()
        val resourceId = cached?.claims?.resourceId ?: failure?.resourceId
        val version = cached?.claims?.version ?: failure?.version
        val effectiveResourceId =
            resourceId?.takeIf { it.isNotBlank() } ?: pluginId.takeIf { seenDrm }

        val purchaseUrl: String? = if (effectiveResourceId.isNullOrBlank()) {
            null
        } else {
            val websiteBase = configManager.config.panoWebsiteUrl.trimEnd('/')
            if (websiteBase.isEmpty()) null else "$websiteBase/addons/$effectiveResourceId"
        }

        val licenseExpiresAtSec = cached?.claims?.expiresAtMs?.let { it / 1000 }
        val licenseLastCheckedSec = (cached?.claims?.issuedAtMs ?: failure?.recordedAtMs)?.let { it / 1000 }

        return mapOf(
            "premium" to premium,
            "licensed" to licensed,
            "licenseStatus" to status.publicId,
            "licenseExpiresAt" to licenseExpiresAtSec,
            "licenseLastChecked" to licenseLastCheckedSec,
            "licenseFailureReason" to failure?.reason?.publicId,
            "licenseFailureMessage" to failure?.message,
            "resourceId" to effectiveResourceId,
            "licenseVersion" to version,
            "purchaseUrl" to purchaseUrl
        )
    }
}

/**
 * Public license status enum exposed to the panel UI.
 */
enum class LicenseStatus(val publicId: String) {
    NOT_PREMIUM("NOT_PREMIUM"),
    LICENSED("LICENSED"),
    MISSING("MISSING"),
    NO_PURCHASE("NO_PURCHASE"),
    EXPIRED("EXPIRED"),
    NETWORK_ERROR("NETWORK_ERROR"),
    JAR_TAMPERED("JAR_TAMPERED"),
    SIGNATURE_INVALID("SIGNATURE_INVALID"),
    VERSION_MISMATCH("VERSION_MISMATCH"),
    AUDIENCE_MISMATCH("AUDIENCE_MISMATCH"),
    PLATFORM_MISMATCH("PLATFORM_MISMATCH"),
    NOT_CONNECTED("NOT_CONNECTED"),
    /** Stored failure was [NOT_CONNECTED] but the panel host now has a Pano token — refresh license fetch. */
    NEEDS_REFRESH("NEEDS_REFRESH"),
    UNKNOWN("UNKNOWN")
}

/**
 * SCREAMING_SNAKE label panels render for a premium resource's license status.
 *
 * Helpers below derive it for plugins (via [PluginLicenseFailure] from [LicenseManager])
 * and for themes (via [com.panomc.platform.license.ThemeLicenseFailure]). The shape mirrors
 * the [LicenseStatus] enum so the panel-ui `LicenseStatusBadge` / `AddonLicenseCard`
 * components can be reused for themes without divergence.
 */
internal fun deriveThemeLicenseStatusLabel(
    theme: com.panomc.platform.UIManager.Companion.InstalledTheme,
    licenseManager: LicenseManager,
): String {
    // Free themes return the sentinel value `panel-ui/LicenseStatusBadge` already hides
    // with `if (status && status !== 'NOT_PREMIUM')`. Keeps the public id stable across
    // plugin / theme cases.
    if (!theme.premium) return "NOT_PREMIUM"
    val cached = licenseManager.getCachedThemeLicense(theme.id)
    if (cached != null && !cached.claims.isExpired()) return LicenseStatus.LICENSED.publicId
    val failure = licenseManager.getThemeFailure(theme.id)
    if (failure != null) return failure.reason.toLicenseStatus().publicId
    // No cache, no failure: boot-time bulk verify hasn't completed yet, or the renewal
    // sweep just dropped expired state. Panel renders "UNKNOWN" as a neutral icon.
    return LicenseStatus.UNKNOWN.publicId
}

/** Same mapping plugins use; promoted from private so the theme view can reuse it. */
internal fun LicenseDeniedReason.toLicenseStatusPublicId(): String =
    this.toLicenseStatus().publicId

private fun LicenseDeniedReason.toLicenseStatus(): LicenseStatus = when (this) {
    LicenseDeniedReason.NOT_CONNECTED -> LicenseStatus.NOT_CONNECTED
    LicenseDeniedReason.NO_PURCHASE -> LicenseStatus.NO_PURCHASE
    LicenseDeniedReason.JAR_HASH_MISMATCH -> LicenseStatus.JAR_TAMPERED
    LicenseDeniedReason.VERSION_MISMATCH -> LicenseStatus.VERSION_MISMATCH
    LicenseDeniedReason.NETWORK_ERROR -> LicenseStatus.NETWORK_ERROR
    LicenseDeniedReason.SIGNATURE_INVALID -> LicenseStatus.SIGNATURE_INVALID
    LicenseDeniedReason.EXPIRED -> LicenseStatus.EXPIRED
    LicenseDeniedReason.AUDIENCE_MISMATCH -> LicenseStatus.AUDIENCE_MISMATCH
    LicenseDeniedReason.PLATFORM_MISMATCH -> LicenseStatus.PLATFORM_MISMATCH
    LicenseDeniedReason.KEY_NOT_AVAILABLE -> LicenseStatus.UNKNOWN
    LicenseDeniedReason.INVALID_RESPONSE -> LicenseStatus.UNKNOWN
    // Theme-only at the moment, but we surface it through the same panel pipeline as
    // JAR_TAMPERED so the UI just shows "files have been modified after install".
    LicenseDeniedReason.FILE_TAMPERED -> LicenseStatus.JAR_TAMPERED
    LicenseDeniedReason.UNKNOWN -> LicenseStatus.UNKNOWN
}
