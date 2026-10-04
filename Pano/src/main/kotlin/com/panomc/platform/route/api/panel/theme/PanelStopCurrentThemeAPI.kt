package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.StoppedCurrentThemeLog
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.ui.ThemeUiController
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelStopCurrentThemeAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val themeUiController: ThemeUiController
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/api/panel/themes", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("password", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val password = data.getString("password")

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = databaseManager.getSqlClient()

        val isPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, password, sqlClient)

        if (!isPasswordCorrect) {
            throw NoPermission()
        }

        val stopped = themeUiController.stop() ?: return Successful()

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            StoppedCurrentThemeLog(
                userId,
                username,
                stopped
            ), sqlClient
        )

        return Successful()
    }
}