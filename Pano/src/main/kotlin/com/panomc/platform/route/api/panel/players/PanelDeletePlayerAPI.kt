package com.panomc.platform.route.api.panel.players


import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.PlayerEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.log.DeletedPlayerLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.*
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.message.PermissionsSnapshotUpdatedMessage
import com.panomc.platform.token.TokenProvider
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.stringSchema


@Endpoint
class PanelDeletePlayerAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val permissionManager: PermissionManager,
    private val serverManager: ServerManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/delete", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", stringSchema()))
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = parameters.pathParameter("username").string
        val currentPassword = data.getString("currentPassword")

        val sqlClient = getSqlClient()

        val userId =
            databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw NotExists()
        val authUserId = authProvider.getUserIdFromRoutingContext(context)

        if (userId == authUserId) {
            throw CantDeleteYourself()
        }

        val isCurrentPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(authUserId, currentPassword, sqlClient)

        if (!isCurrentPasswordCorrect) {
            throw CurrentPasswordNotCorrect()
        }

        val isUserAdmin = authProvider.isUserAdmin(userId)

        if (isUserAdmin) {
            val isAdmin = context.get<Boolean>("isAdmin") ?: false

            if (!isAdmin) {
                throw NoPermission()
            }

            val usersInAdminGroup = permissionManager.getUserIdsWithNode("*")

            if (usersInAdminGroup.size == 1) {
                throw LastAdmin()
            }
        }

        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        PluginEventManager.getPanoEventListeners<PlayerEventListener>().forEach { eventHandler ->
            eventHandler.onDelete(user)
        }

        tokenProvider.invalidateTokensBySubject(userId.toString(), sqlClient)
        databaseManager.notificationDao.deleteAllByUserId(userId, sqlClient)
        databaseManager.panelNotificationDao.deleteAllByUserId(userId, sqlClient)
        databaseManager.postDao.updateUserIdByUserId(userId, -1, sqlClient)
        databaseManager.banHistoryDao.deleteByUserId(userId, sqlClient)
        databaseManager.permissionNodeDao.deleteByUserId(userId, sqlClient)

        permissionManager.refresh()

        val tickets = databaseManager.ticketDao.getByUserId(userId, sqlClient)

        val ticketIdList = JsonArray(tickets.map { it.id })

        if (ticketIdList.size() != 0) {
            databaseManager.ticketMessageDao.deleteByTicketIdList(ticketIdList, sqlClient)
            databaseManager.ticketDao.delete(ticketIdList, sqlClient)
        }

        // admin stuff
        databaseManager.panelActivityLogDao.deleteByUserId(userId, sqlClient)
        databaseManager.panelConfigDao.deleteByUserId(userId, sqlClient)

        databaseManager.userDao.deleteById(userId, sqlClient)

        val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(DeletedPlayerLog(authUserId, authUsername, username), sqlClient)

        broadcastPermissionsSnapshotUpdated()

        return Successful()
    }

    private fun broadcastPermissionsSnapshotUpdated() {
        val msg = PermissionsSnapshotUpdatedMessage()
        serverManager.getConnectedServers().keys
            .filter { it.settings.permissionIntegration }
            .forEach { srv ->
                serverManager.sendMessage(msg, srv)
            }
    }
}
