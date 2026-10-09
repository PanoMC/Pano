package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.BannedIpListFilter
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.PlayerStatus
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelGetPlayersAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager
) : PanelApi() {
    override val paths = listOf(Path("/players", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(
                optionalParam(
                    "status",
                    arraySchema()
                        .items(enumSchema(*PlayerStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("view", stringSchema()))
            .queryParameter(optionalParam("permissionGroup", stringSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("ipBanStatus", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)

        val playerStatus = PlayerStatus.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: PlayerStatus.ALL.name
        )

        val view = PlayersView.entries.find {
            it.name == parameters.queryParameter("view")?.string
        } ?: PlayersView.PLAYERS
        val page = Paging.request(context)
        val permissionGroupName = parameters.queryParameter("permissionGroup")?.string
        val search = parameters.queryParameter("search")?.string?.trim()?.takeIf { it.isNotEmpty() }

        val sqlClient = databaseManager.getSqlClient()

        if (view == PlayersView.BANS) {
            return getBanHistoryResult(page, search, sqlClient)
        }

        if (view == PlayersView.IP_BANS) {
            val listFilter = when (parameters.queryParameter("ipBanStatus")?.string?.uppercase()) {
                "HISTORY" -> BannedIpListFilter.HISTORY
                else -> BannedIpListFilter.ACTIVE
            }
            return getIpBanResult(page, search, listFilter, sqlClient)
        }

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

        Paging.requireInRange(page, count)

        val userList =
            if (userIdsWithGroup != null) {
                if (permissionGroupName == "-") {
                    if (search != null) {
                        databaseManager.userDao.getByPageExcludingIdsAndSearch(
                            userIdsWithGroup,
                            page.number.toLong(),
                            page.size,
                            search,
                            sqlClient
                        )
                    } else {
                        databaseManager.userDao.getByPageExcludingIds(userIdsWithGroup, page.number.toLong(), page.size, sqlClient)
                    }
                } else {
                    if (search != null) {
                        databaseManager.userDao.getByIdsPageAndSearch(userIdsWithGroup, page.number.toLong(), page.size, search, sqlClient)
                    } else {
                        databaseManager.userDao.getByIdsPage(userIdsWithGroup, page.number.toLong(), page.size, sqlClient)
                    }
                }
            } else if (search != null) {
                databaseManager.userDao.getAllByPageAndStatusAndSearch(playerStatus, search, page.limit, page.offset, sqlClient)
            } else
                databaseManager.userDao.getAllByPageAndStatus(playerStatus, page.limit, page.offset, sqlClient)

        val permissionGroup =
            if (permissionGroupName != null) permissionManager.getPermissionGroupByName(permissionGroupName) else null

        if (userList.isEmpty()) {
            return Successful(payload(listOf(), count, page, permissionGroup))
        }

        val userIdList = userList.map { it.id }
        val usernameList = userList.map { it.username }
        val userIdTicketCountMap = databaseManager.ticketDao.countByUserIdList(userIdList, sqlClient)
        val usernameInGameMap = databaseManager.serverPlayerDao.existsByUsernameList(usernameList, sqlClient)

        val players = userList.map {
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

        return Successful(payload(players, count, page, permissionGroup))
    }

    private suspend fun getBanHistoryResult(
        page: PageRequest,
        search: String?,
        sqlClient: io.vertx.sqlclient.SqlClient
    ): Result {
        val count = if (search != null) {
            databaseManager.banHistoryDao.countBySearch(search, sqlClient)
        } else {
            databaseManager.banHistoryDao.count(sqlClient)
        }

        Paging.requireInRange(page, count)

        val banHistoryList = if (search != null) {
            databaseManager.banHistoryDao.getAllByPageAndSearch(search, page.limit, page.offset, sqlClient)
        } else {
            databaseManager.banHistoryDao.getAllByPage(page.limit, page.offset, sqlClient)
        }

        if (banHistoryList.isEmpty()) {
            return Successful(payload(listOf(), count, page, null))
        }

        val usersById = databaseManager.userDao
            .getAllByIds(banHistoryList.map { it.userId }.distinct(), sqlClient)
            .associateBy { it.id }

        val usernameInGameMap = databaseManager.serverPlayerDao
            .existsByUsernameList(usersById.values.map { it.username }, sqlClient)

        val players = banHistoryList.map { banHistory ->
            val user = usersById[banHistory.userId]
            val username = user?.username ?: "deleted-user-${banHistory.userId}"

            val playerData = if (user != null) {
                JsonObject.mapFrom(user)
            } else {
                JsonObject()
                    .put("id", -1L)
                    .put("username", username)
                    .put("registerDate", banHistory.createdAt)
                    .put("lastLoginDate", 0L)
            }

            playerData.put("isBanned", if (user != null) BanUtil.isBanned(user) else false)
            playerData.put("inGame", usernameInGameMap[username] ?: false)
            playerData.put("permissionGroup", if (user != null) permissionManager.getPermissionGroup(user.id) else "-")
            playerData.put("isEmailVerified", user?.emailVerified ?: false)
            playerData.put("selected", false)
            playerData.put("bannedAt", banHistory.createdAt)
            playerData.put("banHistoryId", banHistory.id)
            playerData.put("banReason", banHistory.reason)
            playerData.put("bannedUntil", banHistory.bannedUntil)
            playerData.put("emailNotified", banHistory.emailNotified)
            playerData.put("bannedBy", banHistory.bannedBy)
            playerData.put("bannedBySystem", banHistory.bannedBySystem)
            playerData.put("source", banHistory.source)

            playerData.remove("password")
            playerData.remove("banned")

            if (com.panomc.platform.Main.IS_DEMO) {
                playerData.remove("registeredIp")
            }

            playerData
        }

        return Successful(payload(players, count, page, null))
    }

    private suspend fun getIpBanResult(
        page: PageRequest,
        search: String?,
        listFilter: BannedIpListFilter,
        sqlClient: io.vertx.sqlclient.SqlClient
    ): Result {
        val nowMs = System.currentTimeMillis()
        val count = if (search != null) {
            databaseManager.bannedIpDao.countByListFilterAndSearch(listFilter, search, nowMs, sqlClient)
        } else {
            databaseManager.bannedIpDao.countByListFilter(listFilter, nowMs, sqlClient)
        }

        Paging.requireInRange(page, count)

        val bannedIpList = if (search != null) {
            databaseManager.bannedIpDao.getAllByPageAndListFilterAndSearch(
                search,
                listFilter,
                nowMs,
                page.limit,
                page.offset,
                sqlClient
            )
        } else {
            databaseManager.bannedIpDao.getAllByPageAndListFilter(listFilter, nowMs, page.limit, page.offset, sqlClient)
        }

        val players = bannedIpList.map { bannedIp ->
            JsonObject()
                .put("id", bannedIp.id)
                .put("ip", bannedIp.ip)
                .put("reason", bannedIp.reason)
                .put("bannedUntil", bannedIp.bannedUntil)
                .put("bannedBy", bannedIp.bannedBy)
                .put("bannedBySystem", bannedIp.bannedBySystem)
                .put("source", bannedIp.source)
                .put("createdAt", bannedIp.createdAt)
                .put("updatedAt", bannedIp.updatedAt)
        }

        return Successful(payload(players, count, page, null))
    }

    private enum class PlayersView {
        PLAYERS,
        BANS,
        IP_BANS
    }

    companion object {
        /** The whole response body: `{ items, page }` plus the `permissionGroup` filter (null when none). */
        fun payload(
            players: List<Any?>,
            count: Long,
            page: PageRequest,
            permissionGroup: Any?
        ): Map<String, Any?> = Paging.response(players, count, page, mapOf("permissionGroup" to permissionGroup))
    }
}
