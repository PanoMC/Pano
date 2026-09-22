package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.backup.ManagedServerBackupService
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * Sets how many backups of one server Pano keeps.
 *
 * A partial update: `keepLast` (full zips, 1..100), `snapshotKeepLast` (snapshots, 1..500) and
 * `snapshotMaxBytes` (the snapshot disk cap, a non-negative number or null; 0 and null both mean
 * no cap) are each changed only when present, so a panel that only knows the first one keeps
 * working. A body with none of them is refused rather than accepted as a no-op.
 *
 * Lowering a limit prunes straight away rather than at the next backup: an operator who has just
 * been told their disk is full expects the number they typed to mean something now.
 */
@Endpoint
class PanelUpdateServerBackupSettingsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val backupService: ManagedServerBackupService,
    private val serverFeatureResolver: ServerFeatureResolver
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/backups/settings", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty("keepLast", intSchema())
                        .optionalProperty("snapshotKeepLast", intSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        val body = parameters.body().jsonObject

        val keepLast = readLimit(body, "keepLast", Server.Companion.ServerSettings.MAX_BACKUP_KEEP_LAST)
        val snapshotKeepLast = readLimit(body, "snapshotKeepLast", Server.Companion.ServerSettings.MAX_SNAPSHOT_KEEP_LAST)

        // Absent and null are different here: absent leaves the cap alone, null removes it.
        val capPresent = body.containsKey("snapshotMaxBytes")
        val snapshotMaxBytes = if (capPresent) readCap(body.getValue("snapshotMaxBytes")) else null

        if (keepLast == null && snapshotKeepLast == null && !capPresent) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        // Whoever can take a backup owns the rest of them (§2.4.17): a node, or a plugin with `backups`.
        serverFeatureResolver.pick(server, ServerFeature.BACKUPS_CREATE)

        val settings = server.settings

        keepLast?.let { settings.backupKeepLast = it }
        snapshotKeepLast?.let { settings.snapshotKeepLast = it }

        if (capPresent) {
            settings.snapshotMaxBytes = snapshotMaxBytes
        }

        databaseManager.serverDao.updateSettingsById(settings, id, sqlClient)

        server.settings = settings

        backupService.applyRetention(server, sqlClient)

        return Successful(
            mapOf(
                "keepLast" to settings.backupKeepLast,
                "snapshotKeepLast" to settings.snapshotKeepLast,
                "snapshotMaxBytes" to settings.snapshotMaxBytes
            )
        )
    }

    /** One of the keep-last limits, or null when the body leaves it alone. */
    private fun readLimit(body: JsonObject, field: String, max: Int): Int? {
        val value = body.getValue(field) ?: return null

        val limit = (value as? Number)?.toInt() ?: throw BadRequest()

        if (limit < 1 || limit > max) {
            throw BadRequest()
        }

        return limit
    }

    /** The disk cap: null or zero for none, otherwise a whole number of bytes. */
    private fun readCap(value: Any?): Long? {
        if (value == null) {
            return null
        }

        val number = value as? Number ?: throw BadRequest()

        val bytes = number.toLong()

        if (bytes < 0 || number.toDouble() != bytes.toDouble()) {
            throw BadRequest()
        }

        return bytes.takeIf { it > 0 }
    }
}
