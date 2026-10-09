package com.panomc.platform.api

/**
 * One address an admin registered somewhere outside Pano and that the API cutover moved, for the compatibility
 * surface of the panel (doc 04 section 7, decision 46): an OAuth redirect URL at Microsoft or a social provider,
 * a payment gateway callback.
 *
 * @property label what the address is for, in words the admin finds in the provider's console ("Microsoft redirect URL").
 * @property url the address as it is now, absolute.
 */
data class ExternalUrl(
    val label: String,
    val url: String
)

/**
 * A plugin lists the addresses it has registered outside Pano by declaring a bean of this type (any Spring
 * stereotype in the plugin's bean context). Old addresses are not kept as aliases, so after the cutover the admin
 * has to update them; `GET /api/v1/panel/compatibility` shows every started plugin's list under `externalUrls`.
 * A provider that throws is logged and skipped, it never breaks the page.
 */
interface ExternalUrlProvider {
    /** The addresses of this provider as they are now. Cheap: it runs on every page load. */
    fun urls(): List<ExternalUrl>
}
