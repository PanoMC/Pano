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
import com.panomc.platform.util.BanUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelUnbanPlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/unban", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(username, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val player =
            databaseManager.userDao.getByUsername(username, sqlClient) ?: throw NotExists()

        if (!BanUtil.isBanned(player)) {
            throw NotBanned()
        }

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(player.id, sqlClient)

        if (userPermissionGroupId != null && userPermissionGroupId != -1L) {
            val userPermissionGroup =
                databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlClient)!!

            val isAdmin = context.get<Boolean>("isAdmin") ?: false

            if (userPermissionGroup.name == "admin" && !isAdmin) {
                throw NoPermission()
            }
        }

        databaseManager.userDao.unbanPlayer(player.id, sqlClient)

        val authUserId = authProvider.getUserIdFromRoutingContext(context)
        val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UnbannedPlayerLog(authUserId, authUsername, username), sqlClient)

        return Successful()
    }
}