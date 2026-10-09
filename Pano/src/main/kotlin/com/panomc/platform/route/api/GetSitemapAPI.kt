package com.panomc.platform.route.api

import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.SitemapEntry
import com.panomc.platform.api.SitemapProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema

/**
 * `GET /api/v1/sitemap` -- every public page of the site as `{ type, params, updatedAt }` entries (doc 04
 * section 8), paged with the common page shape (default 100). Core entries come from [CoreSitemapProvider],
 * plugin entries from the [SitemapProvider] beans of started plugins. The URL map of the front-end builds the URLs.
 */
@Endpoint
class GetSitemapAPI(
    private val databaseManager: DatabaseManager,
    private val pluginManager: PluginManager,
    private val coreProviders: List<SitemapProvider>
) : Api() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/sitemap", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Every public page of the site, core and plugins, for building a sitemap.",
        tag = "site",
        paginatedItem = objectSchema()
            .requiredProperty("type", stringSchema())
            .requiredProperty("params", objectSchema())
            .optionalProperty("updatedAt", intSchema()),
        errors = listOf(InvalidFields::class, PageNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    override suspend fun handle(context: RoutingContext): Result {
        val page = Paging.request(context, DEFAULT_PAGE_SIZE)
        val sqlClient = getSqlClient()

        val pluginProviders = pluginManager.getActivePanoPlugins().associate { plugin ->
            plugin.pluginId to plugin.pluginBeanContext.getBeansOfType(SitemapProvider::class.java).values.toList()
        }

        val entries = collect(coreProviders, pluginProviders, sqlClient)

        return Successful(payload(entries, page))
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100

        private val logger = LoggerFactory.getLogger(GetSitemapAPI::class.java)

        /**
         * Asks every provider and merges the answers. A plugin entry whose type is not `<pluginId>:<name>` is
         * dropped, a provider that throws is skipped. The result is ordered by type, then by params, so a page
         * boundary is stable between calls.
         */
        suspend fun collect(
            coreProviders: List<SitemapProvider>,
            pluginProviders: Map<String, List<SitemapProvider>>,
            sqlClient: SqlClient
        ): List<SitemapEntry> {
            val entries = mutableListOf<SitemapEntry>()

            coreProviders.forEach { provider ->
                entries += ask(provider, "core", sqlClient).filter { entry -> entry.type.isNotBlank() && ":" !in entry.type }
            }

            pluginProviders.forEach { (pluginId, providers) ->
                providers.forEach { provider ->
                    entries += ask(provider, pluginId, sqlClient).filter { entry ->
                        val own = entry.type.startsWith("$pluginId:") && entry.type.length > pluginId.length + 1

                        if (!own) {
                            logger.warn("Sitemap entry type '{}' of plugin '{}' must start with '{}:'; dropped", entry.type, pluginId, pluginId)
                        }

                        own
                    }
                }
            }

            return entries.sortedWith(compareBy<SitemapEntry> { it.type }.thenBy { sortKey(it.params) })
        }

        private suspend fun ask(provider: SitemapProvider, owner: String, sqlClient: SqlClient): List<SitemapEntry> =
            try {
                provider.entries(sqlClient)
            } catch (e: Exception) {
                logger.error("Sitemap provider {} of '{}' failed; skipped", provider.javaClass.name, owner, e)

                emptyList()
            }

        private fun sortKey(params: Map<String, Any?>) =
            params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

        /** The response body for [page] of the merged [entries]. */
        fun payload(entries: List<SitemapEntry>, page: PageRequest): Map<String, Any?> {
            Paging.requireInRange(page, entries.size.toLong())

            val items = entries.drop(page.offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(page.limit).map { entry ->
                val item = linkedMapOf<String, Any?>("type" to entry.type, "params" to entry.params)

                entry.updatedAt?.let { item["updatedAt"] = it }

                item
            }

            return Paging.response(items, entries.size.toLong(), page)
        }
    }
}

/** The core's own pages in the sitemap: the published posts (`type: "post"`, `params: { url }`). */
@Component
class CoreSitemapProvider(private val databaseManager: DatabaseManager) : SitemapProvider {
    override suspend fun entries(sqlClient: SqlClient): List<SitemapEntry> =
        databaseManager.postDao.getPublishedUrlsAndDates(sqlClient).map { (url, date) ->
            SitemapEntry("post", mapOf("url" to url), date)
        }
}
