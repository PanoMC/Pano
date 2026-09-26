package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.PanoBackupTag
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Starts a backup of the whole Pano (`POST /api/panel/pano-backups`). With a `passphrase` the
 * archive is end-to-end encrypted (lost passphrase = unrecoverable); without one it is a plain
 * archive, which is also the export format another Pano or Pano Host imports.
 */
@Endpoint
class PanelCreatePanoBackupAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().optionalProperty("passphrase", stringSchema())))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        val body = getParameters(context).body()?.jsonObject
        val passphrase = PanoBackupRoutes.passphrase(body?.getString("passphrase"))

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, getSqlClient())

        val job = PanoBackupRoutes.startJob {
            panoBackupManager.service.startCreate(passphrase, PanoBackupTag.MANUAL, username)
        }

        return Successful(mapOf("job" to job.toJson()))
    }
}
