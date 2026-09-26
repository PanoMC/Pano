package com.panomc.platform.route.api.panel.panoBackup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePanoBackupsPermission
import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.PanoBackupStore
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait

/** Deletes one local backup (`DELETE /api/panel/pano-backups/:id`). */
@Endpoint
class PanelDeletePanoBackupAPI(
    private val authProvider: AuthProvider,
    private val panoBackupManager: PanoBackupManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/pano-backups/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePanoBackupsPermission(), context)

        val id = getParameters(context).pathParameter("id").string

        if (!PanoBackupStore.isValidId(id)) {
            throw NotExists()
        }

        val deleted = context.vertx().executeBlocking { panoBackupManager.store.delete(id) }.coAwait()

        if (!deleted) {
            throw NotExists()
        }

        return Successful()
    }
}
