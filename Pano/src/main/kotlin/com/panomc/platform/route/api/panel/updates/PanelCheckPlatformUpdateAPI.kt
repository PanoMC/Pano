package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.CheckedUpdatesLog
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.core.http.HttpMethod

@Endpoint
class PanelCheckPlatformUpdateAPI(
    private val updateManager: UpdateManager,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/updates/platform", RouteType.GET))

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return false
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                optionalParam(
                    "type",
                    stringSchema()
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val type = context.request().getParam("type")

        if (type == "RESOURCES") {
            updateManager.checkResourceUpdates(false)
        } else {
            updateManager.checkUpdates()
        }

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            CheckedUpdatesLog(
                userId,
                username,
            ), sqlClient
        )

        return Successful()
    }
}