package com.panomc.platform.route.api.panel.frontend.mode

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.ui.CustomAppActive
import com.panomc.platform.ui.CustomAppInstaller
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** Removes an uploaded custom app. Refused while it is the active front-end. */
@Endpoint
class PanelDeleteCustomAppAPI(
    private val customAppInstaller: CustomAppInstaller,
    private val uiManager: UIManager,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/custom-apps/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val id = getParameters(context).pathParameter("id").string

        if (!customAppInstaller.exists(id)) {
            throw NotFound()
        }

        if (uiManager.isCustomAppActive(id)) {
            throw CustomAppActive(id)
        }

        customAppInstaller.delete(id)

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(DeletedCustomAppLog(userId, username, id), sqlClient)

        return Successful()
    }
}
