package com.panomc.platform.route.api.panel.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UnbannedPlayerLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
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
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)

        val player = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(player, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val playerId =
            databaseManager.userDao.getUserIdFromUsername(player, sqlClient) ?: throw NotExists()

        val isBanned = databaseManager.userDao.isBanned(playerId, sqlClient)

        if (!isBanned) {
            throw NotBanned()
        }

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(playerId, sqlClient)!!

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlClient)!!

        val isAdmin = context.get<Boolean>("isAdmin") ?: false

        if (userPermissionGroup.name == "admin" && !isAdmin) {
            throw NoPermission()
        }

        databaseManager.userDao.unbanPlayer(playerId, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UnbannedPlayerLog(userId, username, player), sqlClient)

        return Successful()
    }
}