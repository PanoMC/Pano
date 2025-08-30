package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetDashboardAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/dashboard", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val result = mutableMapOf<String, Any?>(
            "gettingStartedBlocks" to mapOf(
                "welcomeBoard" to false
            ),
            "tickets" to emptyList<Any?>(),
            "lastRegisters" to emptyList<Any?>()
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

        if (authProvider.hasPermission(userId, ManageTicketsPermission(), context) && ticketCount != 0L) {
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

        if (authProvider.hasPermission(userId, ManagePlayersPermission(), context)) {
            val users = databaseManager.userDao.getLast5Register(sqlClient)
            val permissionGroupIdList = users.map { it.permissionGroupId }
            val userIdList = users.map { it.id }
            val usernameList = users.map { it.username }
            val permissions = databaseManager.permissionGroupDao.byListOfId(permissionGroupIdList, sqlClient)
            val userIdTicketCountMap = databaseManager.ticketDao.countByUserIdList(userIdList, sqlClient)
            val usernameInGameMap = databaseManager.serverPlayerDao.existsByUsernameList(usernameList, sqlClient)

            result["lastRegisters"] = users.map {
                val user = JsonObject.mapFrom(it)

                user.put("isBanned", it.banned)
                user.put("inGame", usernameInGameMap[it.username])
                user.put("permissionGroup", permissions[it.permissionGroupId]?.name ?: "-")
                user.put("ticketCount", userIdTicketCountMap[it.id])
                user.put("isEmailVerified", it.emailVerified)

                user.remove("password")
                user.remove("banned")

                user
            }
        }

        return Successful(result)
    }
}