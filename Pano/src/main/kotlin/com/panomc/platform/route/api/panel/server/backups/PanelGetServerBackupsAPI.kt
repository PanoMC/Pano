package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * The backups Pano has for one managed server, plus its retention settings.
 *
 * Each row carries the backups v2 columns — `mode`, `scope`, `pinned`, `fileCount`, `storedBytes`,
 * `include`, `exclude` — and `settings` the three retention knobs: `keepLast` for full zips,
 * `snapshotKeepLast` and `snapshotMaxBytes` (null for no disk cap) for snapshots.
 *
 * Read from Pano's own rows and not from the node: the list has to work while the node is offline,
 * which is precisely when somebody wants to know what copies exist.
 */
@Endpoint
class PanelGetServerBackupsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        // Whoever can take a backup can list them (§2.4.17): the node, or a plugin with `backups`.
        // The rows are Pano's either way; this only refuses a server nobody could back up.
        serverFeatureResolver.pick(server, ServerFeature.BACKUPS_CREATE)

        val backups = databaseManager.serverBackupDao.getAllByServerId(id, sqlClient)

        return Successful(
            mapOf(
                "backups" to backups.map { it.toPublicJsonObject() },
                // Both shapes on purpose: `settings` is where the panel reads it from, and the
                // flat key stays so a caller written against the first draft keeps working.
                "settings" to mapOf(
                    "keepLast" to server.settings.backupKeepLast,
                    "snapshotKeepLast" to server.settings.snapshotKeepLast,
                    "snapshotMaxBytes" to server.settings.snapshotMaxBytes
                ),
                "keepLast" to server.settings.backupKeepLast
            )
        )
    }
}
