package com.panomc.platform.api

import io.vertx.sqlclient.SqlClient

/**
 * One page of a plugin's site for `GET /api/v1/sitemap`.
 *
 * An entry says what the page is, not where it lives: [type] names the kind of page and [params] are the values
 * the front-end's URL map needs to build its address (`{"url": "hello"}` for a post). The type of a plugin entry
 * is `<pluginId>:<name>` (`pano-plugin-market:product`); an entry whose type does not start with the plugin's own
 * id is dropped. Core types are bare (`post`).
 *
 * @property updatedAt last change in epoch milliseconds, or null when unknown.
 */
data class SitemapEntry(
    val type: String,
    val params: Map<String, Any?>,
    val updatedAt: Long? = null
)

/**
 * A plugin adds its public pages to the sitemap by declaring a bean of this type (any Spring stereotype in the
 * plugin's bean context). Pano asks every started plugin's providers when `GET /api/v1/sitemap` is called, in
 * the order of type then params; a provider that throws is logged and skipped, it never breaks the sitemap.
 */
interface SitemapProvider {
    /** All entries of this provider. Pano pages the merged list, so return them all. */
    suspend fun entries(sqlClient: SqlClient): List<SitemapEntry>
}
