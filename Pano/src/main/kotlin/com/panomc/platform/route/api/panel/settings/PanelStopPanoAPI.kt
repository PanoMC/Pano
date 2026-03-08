package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.StoppedPanoLog
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking


@Endpoint
class PanelStopPanoAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val vertx: Vertx,
    private val main: Main
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings/stop-pano", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("password", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val password = data.getString("password")

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = getSqlClient()

        val isPasswordCorrect = databaseManager.userDao.isPasswordCorrectWithId(userId, password, sqlClient)

        if (!isPasswordCorrect) {
            throw NoPermission()
        }

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            StoppedPanoLog(
                userId,
                username
            ), sqlClient
        )

        vertx.executeBlocking {
            runBlocking {
                delay(500)
            }
            runBlocking { main.shutdown() }
        }

        return Successful()
    }
}
