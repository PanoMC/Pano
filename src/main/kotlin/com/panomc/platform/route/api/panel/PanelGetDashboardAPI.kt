package com.panomc.platform.route.api.panel

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.Permission.ACCESS_PANEL
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetDashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/dashboard")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val result = mutableMapOf<String, Any?>(
            "gettingStartedBlocks" to mapOf(
                "welcomeBoard" to false
            ),
            "registeredPlayerCount" to 0,
            "postCount" to 0,
            "ticketCount" to 0,
            "openTicketCount" to 0,
            "tickets" to mutableListOf<Map<String, Any?>>(),
            "adminCount" to 0,
            "connectedServerCount" to 0
        )

        val sqlConnection = createConnection(databaseManager, context)

        val isUserInstalled =
            databaseManager.systemPropertyDao.isUserInstalledSystemByUserId(userId, sqlConnection)

        if (isUserInstalled) {
            val systemProperty = databaseManager.systemPropertyDao.getValue(
                SystemProperty(
                    option = "show_getting_started"
                ),
                sqlConnection
            ) ?: throw Error(ErrorCode.UNKNOWN)

            result["gettingStartedBlocks"] = mapOf(
                "welcomeBoard" to systemProperty.value.toBoolean()
            )
        }

        val userCount = databaseManager.userDao.count(
            sqlConnection
        )

        result["registeredPlayerCount"] = userCount

        val postCount = databaseManager.postDao.count(
            sqlConnection
        )

        result["postCount"] = postCount

        val ticketCount = databaseManager.ticketDao.count(
            sqlConnection
        )

        result["ticketCount"] = ticketCount

        if (ticketCount == 0L) {
            return Successful(result)
        }

        val openTicketCount = databaseManager.ticketDao.countOfOpenTickets(sqlConnection)

        result["openTicketCount"] = openTicketCount

        val permissionId = databaseManager.permissionDao.getPermissionId(
            Permission(name = ACCESS_PANEL.value, iconName = ""),
            sqlConnection
        )
        val permissionGroupsByPermissionId =
            databaseManager.permissionGroupPermsDao.getPermissionGroupPermsByPermissionId(permissionId, sqlConnection)

        var adminCount = 0L

        val permissionGroupList = permissionGroupsByPermissionId.toMutableList()

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupId(PermissionGroup(name = "admin"), sqlConnection)

        val userCountOfAdminPermission =
            databaseManager.userDao.getCountOfUsersByPermissionGroupId(adminPermissionGroupId!!, sqlConnection)

        adminCount += userCountOfAdminPermission

        permissionGroupList.forEach { permissionGroupPerm ->
            adminCount += databaseManager.userDao.getCountOfUsersByPermissionGroupId(
                permissionGroupPerm.permissionGroupId,
                sqlConnection
            )
        }

        result["adminCount"] = adminCount

        databaseManager.serverDao

        val tickets = databaseManager.ticketDao.getLast5Tickets(sqlConnection)

        val userIdList = tickets.distinctBy { it.userId }.map { it.userId }

        val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlConnection)

        val categoryIdList = tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }
        var ticketCategoryList: Map<Long, TicketCategory> = mapOf()

        if (categoryIdList.isNotEmpty()) {
            ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlConnection)
        }

        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title,
                    "category" to
                            if (ticket.categoryId == -1L)
                                TicketCategory()
                            else
                                ticketCategoryList.getOrDefault(
                                    ticket.categoryId,
                                    TicketCategory()
                                ),
                    "writer" to mapOf(
                        "username" to usernameList[ticket.userId]
                    ),
                    "date" to ticket.date,
                    "lastUpdate" to ticket.lastUpdate,
                    "status" to ticket.status.value
                )
            )
        }

        result["tickets"] = ticketDataList

        return Successful(result)
    }
}