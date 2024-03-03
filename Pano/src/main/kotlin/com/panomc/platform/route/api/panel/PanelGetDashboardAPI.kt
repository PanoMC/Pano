package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission.MANAGE_TICKETS
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetDashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/dashboard", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val result = mutableMapOf<String, Any?>(
            "gettingStartedBlocks" to mapOf(
                "welcomeBoard" to false
            ),
        )

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

        val ticketCount = databaseManager.ticketDao.count(sqlClient)

        if (authProvider.hasPermission(userId, MANAGE_TICKETS, context) && ticketCount != 0L) {
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
                        "status" to ticket.status
                    )
                )
            }

            result["tickets"] = ticketDataList
        }

        return Successful(result)
    }
}