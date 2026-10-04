package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.server.ServerAuthProvider
import com.panomc.platform.server.plugins.PanoPluginJarProvider
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * The Pano plugin build a connected plugin should replace itself with
 * (`GET /api/server/pano-plugin/jar`).
 *
 * The download half of `PANO_PLUGIN_UPDATE`. A linked server has no node to fetch anything for it,
 * so its plugin fetches its own successor, and it does so here rather than from GitHub: it already
 * holds a token Pano recognises, the SHA-256 it was told to expect is of this very file, and Pano
 * may be serving a development build that exists nowhere else.
 *
 * Authenticated as the plugin (`Authorization: Bearer <platform token>`), the same credential the
 * plugin's transfers use. The jar itself is no secret — it is a published release — but serving it
 * only to servers keeps this from being an anonymous mirror, and the token is also what says which
 * server is asking and therefore which platform's build it needs: the Bukkit family all get the
 * Spigot jar, Waterfall gets BungeeCord's, Quilt gets Fabric's. There is no parameter to ask for
 * another one, because a plugin has no business installing a build for a different platform.
 *
 * 404 when the server's software has no Pano plugin or no build can be had right now.
 */
@Endpoint
class ServerPanoPluginJarAPI(
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager,
    private val serverAuthProvider: ServerAuthProvider,
    private val panoPluginJarProvider: PanoPluginJarProvider
) : Api() {
    override val paths = listOf(Path(PanoPluginUpdateService.SERVER_JAR_PATH, RouteType.GET))

    // Minecraft plugin surface, like ServerConnectAPI: an update started before maintenance mode
    // was switched on must still be able to finish.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        if (!setupManager.isSetupDone()) {
            return InstallationRequired()
        }

        if (!serverAuthProvider.isAuthenticated(context)) {
            return InvalidToken()
        }

        val serverId = serverAuthProvider.getServerIdFromRoutingContext(context)

        val server = databaseManager.serverDao.getById(serverId, getSqlClient()) ?: return InvalidToken()

        val jar = panoPluginJarProvider.prepare(server.type) ?: return NotExists()

        context.response()
            .putHeader("Content-Type", "application/java-archive")
            .putHeader("Content-Length", jar.size.toString())
            .putHeader("Content-Disposition", "attachment; filename=\"${jar.fileName}\"")
            // What the push said to expect, repeated so a plugin that fetched after a development
            // rebuild can tell a changed jar from a corrupted download.
            .putHeader(SHA256_HEADER, jar.sha256)
            .putHeader("X-Content-Type-Options", "nosniff")
            .putHeader("Cache-Control", "no-store")
            .sendFile(jar.file.absolutePath)
            .coAwait()

        return null
    }

    companion object {
        const val SHA256_HEADER = "X-Pano-Sha256"
    }
}
