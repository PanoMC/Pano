package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.PanoBackupStore
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait

/**
 * Restores a local backup over this Pano (`POST /api/panel/pano-backups/:id/restore`): verified
 * first, then maintenance mode, a pre-restore safety backup, the restore itself and a restart.
 * Re-authenticated with the admin's password; poll `GET /api/panel/pano-backups/job`.
 */
@Endpoint
class PanelRestorePanoBackupAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups/:id/restore", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                        .optionalProperty("passphrase", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").string
        val body = parameters.body().jsonObject

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!databaseManager.userDao.isPasswordCorrectWithId(userId, body.getString("currentPassword"), getSqlClient())) {
            throw CurrentPasswordNotCorrect()
        }

        if (!PanoBackupStore.isValidId(id)) {
            throw NotExists()
        }

        val store = panoBackupManager.store

        context.vertx().executeBlocking { store.get(id) }.coAwait() ?: throw NotExists()

        val passphrase = PanoBackupRoutes.passphrase(body.getString("passphrase"))

        val job = PanoBackupRoutes.startJob {
            panoBackupManager.service.startRestore(store.archiveFile(id), passphrase, deleteSource = false)
        }

        return Successful(mapOf("job" to job.toJson()))
    }
}
