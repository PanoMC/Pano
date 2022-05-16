package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import kotlin.math.ceil

@Endpoint
class PlayersPageInitAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/initPage/playersPage")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val pageType = data.getInteger("pageType")
        val page = data.getInteger("page")

        val sqlConnection = createConnection(databaseManager, context)

        val count = databaseManager.userDao.countByPageType(pageType, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toInt()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        val userList = databaseManager.userDao.getAllByPageAndPageType(page, pageType, sqlConnection)

        val playerList = mutableListOf<Map<String, Any>>()

        val result = mutableMapOf(
            "players" to playerList,
            "playerCount" to count,
            "totalPage" to totalPage
        )

        if (userList.isEmpty()) {
            return Successful(result)
        }

        val addPlayerToList =
            { user: Map<String, Any?>, mutablePlayerList: MutableList<Map<String, Any>>, ticketCount: Int, permissionGroup: PermissionGroup? ->
                mutablePlayerList.add(
                    mapOf(
                        "id" to user["id"] as Int,
                        "username" to user["username"] as String,
                        "email" to user["email"] as String,
                        "permissionGroupId" to user["permissionGroupId"] as Int,
                        "permissionGroup" to (permissionGroup?.name ?: "-"),
                        "ticketCount" to ticketCount,
                        "registerDate" to user["registerDate"] as Long
                    )
                )
            }

        val getPlayerData: suspend (Map<String, Any>) -> Unit = getPlayerData@{ user ->
            val count = databaseManager.ticketDao.countByUserID(
                user["id"] as Int,
                sqlConnection
            )

            if (user["permissionGroupId"] as Int == -1) {
                addPlayerToList(user, playerList, count, null)

                return@getPlayerData
            }

            val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupByID(
                user["permissionGroupId"] as Int,
                sqlConnection
            )

            if (permissionGroup == null) {
                throw Error(ErrorCode.UNKNOWN)
            }

            addPlayerToList(user, playerList, count, permissionGroup)
        }

        userList.forEach {
            getPlayerData(it)
        }

        return Successful(result)
    }
}