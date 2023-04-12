package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission.ACCESS_PANEL
import com.panomc.platform.auth.PanelPermission.MANAGE_TICKETS
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TimeUtil.toGroupGetCountAndDates
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema

@Endpoint
class PanelGetDashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/dashboard", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(
                param(
                    "period",
                    arraySchema()
                        .items(enumSchema(*DashboardPeriodType.values().map { it.period }.toTypedArray()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val period = DashboardPeriodType.valueOf(
            period = parameters.queryParameter("period")?.jsonArray?.first() as String? ?: "week"
        ) ?: DashboardPeriodType.WEEK

        val result = mutableMapOf<String, Any?>(
            "gettingStartedBlocks" to mapOf(
                "welcomeBoard" to false
            ),
            "onlinePlayerCount" to 0,
            "registeredPlayerCount" to 0,
            "postCount" to 0,
            "adminCount" to 0,
            "connectedServerCount" to 0,
            "newRegisterCount" to 0,
            "period" to period.period,
            "websiteActivityDataList" to mutableMapOf<String, Any?>(),
            "ticketCount" to 0
        )

        if (authProvider.hasPermission(userId, MANAGE_TICKETS, context)) {
            result.putAll(
                mapOf(
                    "openTicketCount" to 0,
                    "tickets" to mutableListOf<Map<String, Any?>>()
                )
            )
        }

        val sqlClient = getSqlClient()

        val isUserInstalled =
            databaseManager.systemPropertyDao.isUserInstalledSystemByUserId(userId, sqlClient)

        if (isUserInstalled) {
            val systemProperty = databaseManager.systemPropertyDao.getByOption(
                "show_getting_started",
                sqlClient
            )!!

            result["gettingStartedBlocks"] = mapOf(
                "welcomeBoard" to systemProperty.value.toBoolean()
            )
        }

        result["registeredPlayerCount"] = databaseManager.userDao.count(sqlClient)

        result["onlinePlayerCount"] = databaseManager.userDao.countOfOnline(sqlClient)

        result["postCount"] = databaseManager.postDao.count(sqlClient)

        val ticketCount = databaseManager.ticketDao.count(sqlClient)

        result["ticketCount"] = ticketCount

        if (authProvider.hasPermission(userId, MANAGE_TICKETS, context) && ticketCount != 0L) {
            val openTicketCount = databaseManager.ticketDao.countOfOpenTickets(sqlClient)

            result["openTicketCount"] = openTicketCount

            val tickets = databaseManager.ticketDao.getLast5Tickets(sqlClient)

            val userIdList = tickets.distinctBy { it.userId }.map { it.userId }

            val usernameList = databaseManager.userDao.getUsernameByListOfId(userIdList, sqlClient)

            val categoryIdList =
                tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }
            var ticketCategoryList: Map<Long, TicketCategory> = mapOf()

            if (categoryIdList.isNotEmpty()) {
                ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlClient)
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
        }

        val permissionId = databaseManager.permissionDao.getPermissionId(
            Permission(name = ACCESS_PANEL.toString(), iconName = ""),
            sqlClient
        )
        val permissionGroupsByPermissionId =
            databaseManager.permissionGroupPermsDao.getPermissionGroupPermsByPermissionId(permissionId, sqlClient)

        var adminCount = 0L

        val permissionGroupList = permissionGroupsByPermissionId.toMutableList()

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlClient)

        val userCountOfAdminPermission =
            databaseManager.userDao.getCountOfUsersByPermissionGroupId(adminPermissionGroupId!!, sqlClient)

        adminCount += userCountOfAdminPermission

        permissionGroupList.forEach { permissionGroupPerm ->
            adminCount += databaseManager.userDao.getCountOfUsersByPermissionGroupId(
                permissionGroupPerm.permissionGroupId,
                sqlClient
            )
        }

        result["adminCount"] = adminCount

        result["newRegisterCount"] = databaseManager.userDao.countOfRegisterByPeriod(period, sqlClient)

        val websiteActivityDataList = result["websiteActivityDataList"] as MutableMap<String, Any?>

        val registerDateList = databaseManager.userDao.getRegisterDatesByPeriod(period, sqlClient)
        websiteActivityDataList["newRegisterData"] = registerDateList.toGroupGetCountAndDates()

        val ticketsDateList = databaseManager.ticketDao.getDatesByPeriod(period, sqlClient)
        websiteActivityDataList["ticketsData"] = ticketsDateList.toGroupGetCountAndDates()

        val websiteViewData = databaseManager.websiteViewDao.getWebsiteViewListByPeriod(period, sqlClient)

        val viewsDateMap = mutableMapOf<Long, Long>()
        val visitorDateMap = mutableMapOf<Long, Long>()

        websiteViewData.forEach { viewData ->
            if (viewsDateMap.containsKey(viewData.date)) {
                viewsDateMap[viewData.date] = viewsDateMap[viewData.date]!!.plus(viewData.times)
            } else {
                viewsDateMap[viewData.date] = viewData.times
            }

            if (visitorDateMap.containsKey(viewData.date)) {
                visitorDateMap[viewData.date] = visitorDateMap[viewData.date]!!.plus(1)
            } else {
                visitorDateMap[viewData.date] = 1
            }
        }
        websiteActivityDataList["visitorData"] = visitorDateMap
        websiteActivityDataList["viewData"] = viewsDateMap

        val connectedServerCount = databaseManager.serverDao.countOfPermissionGranted(sqlClient)

        result["connectedServerCount"] = connectedServerCount

        return Successful(result)
    }
}