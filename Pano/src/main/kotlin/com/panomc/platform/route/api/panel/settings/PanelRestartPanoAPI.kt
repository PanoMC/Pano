package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.PlatformStateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.RestartedPanoLog
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


@Endpoint
class PanelRestartPanoAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val vertx: Vertx,
    private val platformStateManager: PlatformStateManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings/restart-pano", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("password", stringSchema())
                        .optionalProperty("background", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val password = data.getString("password")
        val background = data.getBoolean("background", false)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = getSqlClient()

        val isPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, password, sqlClient)

        if (!isPasswordCorrect) {
            throw NoPermission()
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            RestartedPanoLog(
                userId,
                username
            ), sqlClient
        )

        // Restart the application by starting a new process and stopping the current one
        CoroutineScope(context.vertx().dispatcher()).launch {
            restartApplication(background)
        }

        return Successful()
    }

    private suspend fun restartApplication(background: Boolean) {
        try {
            // Container mode (exit 75 to the launcher) is handled inside PlatformStateManager.restart.
            platformStateManager.restart(background)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
