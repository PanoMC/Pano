package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.PlayerStatus
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetPlayersAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                optionalParam(
                    "status",
                    arraySchema()
                        .items(enumSchema(*PlayerStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("permissionGroup", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)

        val playerStatus = PlayerStatus.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: PlayerStatus.ALL.name
        )

        val page = parameters.queryParameter("page")?.long ?: 1L
        val permissionGroupName = parameters.queryParameter("permissionGroup")?.string
        val search = parameters.queryParameter("search")?.string

        val sqlClient = databaseManager.getSqlClient()

        var userIdsWithGroup: List<Long>? = null

        if (playerStatus == PlayerStatus.HAS_PERM) {
            userIdsWithGroup = permissionManager.getUserIdsWithPermission(AccessPanelPermission()).toList()
        }

        if (permissionGroupName != null) {
            userIdsWithGroup = permissionManager.getUserIdsInGroup(permissionGroupName).toList()

            if (permissionGroupName != "-") {
                val exists = permissionManager.groupExists(permissionGroupName)
                if (!exists) throw NotExists()
            }
        }

        val count =
            if (userIdsWithGroup != null) {
                if (permissionGroupName == "-") {
                    if (search != null) {
                        databaseManager.userDao.countExcludingIdsAndSearch(userIdsWithGroup, search, sqlClient)
                    } else {
                        databaseManager.userDao.countExcludingIds(userIdsWithGroup, sqlClient)
                    }
                } else {
                    if (search != null) {
                        databaseManager.userDao.countByIdsAndSearch(userIdsWithGroup, search, sqlClient)
                    } else {
                        databaseManager.userDao.countByIds(userIdsWithGroup, sqlClient)
                    }
                }
            } else if (search != null) {
                databaseManager.userDao.countByStatusAndSearch(playerStatus, search, sqlClient)
            } else
                databaseManager.userDao.countByStatus(playerStatus, sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page !in 1..totalPage) {
            throw PageNotFound()
        }

        val userList =
            if (userIdsWithGroup != null) {
                if (permissionGroupName == "-") {
                    if (search != null) {
                        databaseManager.userDao.getByPageExcludingIdsAndSearch(
                            userIdsWithGroup,
                            page,
                            10,
                            search,
                            sqlClient
                        )
                    } else {
                        databaseManager.userDao.getByPageExcludingIds(userIdsWithGroup, page, 10, sqlClient)
                    }
                } else {
                    if (search != null) {
                        databaseManager.userDao.getByIdsPageAndSearch(userIdsWithGroup, page, 10, search, sqlClient)
                    } else {
                        databaseManager.userDao.getByIdsPage(userIdsWithGroup, page, 10, sqlClient)
                    }
                }
            } else if (search != null) {
                databaseManager.userDao.getAllByPageAndStatusAndSearch(page, playerStatus, search, sqlClient)
            } else
                databaseManager.userDao.getAllByPageAndStatus(page, playerStatus, sqlClient)

        val result = mutableMapOf<String, Any?>(
            "playerCount" to count,
            "totalPage" to totalPage,
            "permissionGroup" to if (permissionGroupName != null) permissionManager.getPermissionGroupByName(permissionGroupName) else null
        )

        if (userList.isEmpty()) {
            return Successful(result)
        }

        val userIdList = userList.map { it.id }
        val usernameList = userList.map { it.username }
        val userIdTicketCountMap = databaseManager.ticketDao.countByUserIdList(userIdList, sqlClient)
        val usernameInGameMap = databaseManager.serverPlayerDao.existsByUsernameList(usernameList, sqlClient)

        result["players"] = userList.map {
            val user = JsonObject.mapFrom(it)

            user.put("isBanned", BanUtil.isBanned(it))
            user.put("inGame", usernameInGameMap[it.username])
            user.put("permissionGroup", permissionManager.getPermissionGroup(it.id))
            user.put("ticketCount", userIdTicketCountMap[it.id])
            user.put("isEmailVerified", it.emailVerified)

            user.remove("password")
            user.remove("banned")

            if (com.panomc.platform.Main.IS_DEMO) {
                user.remove("registeredIp")
            }

            user
        }

        return Successful(result)
    }
}