package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.util.ImageValidationUtil
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.schema.CoreSchemas

/**
 * `GET /api/v1/servers` -- the public server list of the site (doc 04 §8).
 *
 * Accepted servers only. The answer is a hand-built map, never a serialised [Server]: `host`,
 * `port`, `remoteAddress` and the AES key must not leave the platform, and a field added to the
 * entity later must not leak through by default.
 */
@Endpoint
class GetServersAPI(
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager
) : Api() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/servers", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The accepted game servers of the site, the main one first; never a host, port or key.",
        tag = "servers",
        response = CoreSchemas.list(CoreSchemas.server).requiredProperty("address", stringSchema())
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()

        val mainServerId = databaseManager.systemPropertyDao.getByOption("main_server", sqlClient)
            ?.value?.toLongOrNull()

        val servers = databaseManager.serverDao.getAllByPermissionGranted(sqlClient)

        return Successful(payload(servers, mainServerId, configManager.config.serverIpAddress))
    }

    companion object {
        /** The whole response body: the accepted servers, the main one first, then by name. */
        fun payload(servers: List<Server>, mainServerId: Long?, address: String): Map<String, Any?> {
            val items = servers
                .filter { it.permissionGranted }
                .sortedWith(
                    compareByDescending<Server> { it.id == mainServerId }
                        .thenBy { displayName(it).lowercase() }
                        .thenBy { it.id }
                )
                .map { toPublic(it, it.id == mainServerId) }

            return mapOf("items" to items, "address" to address)
        }

        fun toPublic(server: Server, main: Boolean): Map<String, Any?> = mapOf(
            "id" to server.id,
            "name" to displayName(server),
            "motd" to server.motd,
            "online" to (server.status == ServerStatus.ONLINE),
            "playerCount" to server.playerCount,
            "maxPlayerCount" to server.maxPlayerCount,
            "version" to server.version,
            "type" to server.type.name,
            "main" to main,
            // The icon is stored as a raster data URL on the row; anything else (an SVG, a
            // malformed value) is dropped by the sanitiser and shows as no icon.
            "iconUrl" to ImageValidationUtil.sanitizeFaviconDataUrl(server.favicon.trim())
        )

        private fun displayName(server: Server) = server.customName?.takeIf { it.isNotBlank() } ?: server.name
    }
}
