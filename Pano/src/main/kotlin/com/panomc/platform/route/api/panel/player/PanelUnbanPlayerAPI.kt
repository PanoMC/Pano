package com.panomc.platform.route.api.panel.player

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotBanned
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelUnbanPlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/unban", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PLAYERS, context)

        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(username, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw NotExists()

        val isBanned = databaseManager.userDao.isBanned(userId, sqlClient)

        if (!isBanned) {
            throw NotBanned()
        }

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(userId, sqlClient)!!

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlClient)!!

        val isAdmin = context.get<Boolean>("isAdmin") ?: false

        if (userPermissionGroup.name == "admin" && !isAdmin) {
            throw NoPermission()
        }

        databaseManager.userDao.unbanPlayer(userId, sqlClient)

        return Successful()
    }
}