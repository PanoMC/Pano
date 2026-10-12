package com.panomc.platform.api

/**
 * No longer read by Pano. Kept so a plugin built against an older Pano, which still declares an
 * [ExternalUrlProvider] bean, keeps loading; new plugins do not implement it.
 */
@Deprecated("Pano no longer reads external URLs; remove the bean.")
data class ExternalUrl(
    val label: String,
    val url: String
)

/**
 * No longer read by Pano: `GET /api/v1/panel/compatibility` has no `externalUrls` any more. Kept only so
 * plugin jars that still declare a bean of this type keep loading.
 */
@Deprecated("Pano no longer reads external URLs; remove the bean.")
interface ExternalUrlProvider {
    @Suppress("DEPRECATION")
    fun urls(): List<ExternalUrl>
}
