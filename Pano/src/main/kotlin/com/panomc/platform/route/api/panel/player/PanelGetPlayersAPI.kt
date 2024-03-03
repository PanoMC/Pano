package com.panomc.platform.route.api.panel.player


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.User
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.PlayerStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetPlayersAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                optionalParam(
                    "status",
                    arraySchema()
                        .items(enumSchema(*PlayerStatus.values().map { it.type }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("permissionGroup", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PLAYERS, context)

        val parameters = getParameters(context)

        val playerStatus =
            PlayerStatus.valueOf(type = parameters.queryParameter("status")?.jsonArray?.first() as String? ?: "all")
                ?: PlayerStatus.ALL
        val page = parameters.queryParameter("page")?.long ?: 1L
        val permissionGroupName = parameters.queryParameter("permissionGroup")?.string

        val sqlClient = getSqlClient()

        var permissionGroup: PermissionGroup? = null

        if (permissionGroupName != null && permissionGroupName != "-") {
            val isTherePermission =
                databaseManager.permissionGroupDao.isThereByName(permissionGroupName, sqlClient)

            if (!isTherePermission) {
                throw NotExists()
            }

            val permissionGroupId =
                databaseManager.permissionGroupDao.getPermissionGroupIdByName(permissionGroupName, sqlClient)!!

            permissionGroup = PermissionGroup(permissionGroupId, permissionGroupName)
        }

        if (permissionGroupName != null && permissionGroupName == "-") {
            permissionGroup = PermissionGroup(name = "-")
        }

        val count =
            if (permissionGroup != null)
                databaseManager.userDao.getCountOfUsersByPermissionGroupId(permissionGroup.id, sqlClient)
            else
                databaseManager.userDao.countByStatus(playerStatus, sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val userList =
            if (permissionGroup != null)
                databaseManager.userDao.getAllByPageAndPermissionGroup(page, permissionGroup.id, sqlClient)
            else
                databaseManager.userDao.getAllByPageAndStatus(page, playerStatus, sqlClient)

        val playerList = mutableListOf<Map<String, Any>>()

        val result = mutableMapOf(
            "players" to playerList,
            "playerCount" to count,
            "totalPage" to totalPage
        )

        if (permissionGroup != null) {
            result["permissionGroup"] = permissionGroup
        }

        if (userList.isEmpty()) {
            return Successful(result)
        }

        val addPlayerToList: suspend (user: User, mutablePlayerList: MutableList<Map<String, Any>>, ticketCount: Long, permissionGroup: PermissionGroup?) -> Unit =
            { user, mutablePlayerList, ticketCount: Long, userPermissionGroup ->
                mutablePlayerList.add(
                    mapOf(
                        "id" to user.id,
                        "username" to user.username,
                        "email" to user.email,
                        "permissionGroupId" to user.permissionGroupId,
                        "permissionGroup" to (userPermissionGroup?.name ?: "-"),
                        "ticketCount" to ticketCount,
                        "registerDate" to user.registerDate,
                        "lastLoginDate" to user.lastLoginDate,
                        "isBanned" to user.banned,
                        "canCreateTicket" to user.canCreateTicket,
                        "isEmailVerified" to user.emailVerified,
                        "lastActivityTime" to user.lastActivityTime,
                        "inGame" to databaseManager.serverPlayerDao.existsByUsername(user.username, sqlClient)
                    )
                )
            }

        val getPlayerData: suspend (User) -> Unit = getPlayerData@{ user ->
            val count = databaseManager.ticketDao.countByUserId(
                user.id,
                sqlClient
            )

            if (user.permissionGroupId == -1L) {
                addPlayerToList(user, playerList, count, null)

                return@getPlayerData
            }

            val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(
                user.permissionGroupId,
                sqlClient
            )

            if (permissionGroup == null) {
                throw NoPermission()
            }

            addPlayerToList(user, playerList, count, permissionGroup)
        }

        userList.forEach {
            getPlayerData(it)
        }

        return Successful(result)
    }
}