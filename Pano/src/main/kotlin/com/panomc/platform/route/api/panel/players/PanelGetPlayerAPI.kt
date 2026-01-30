package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlin.math.ceil

@Endpoint
class PanelGetPlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("username", stringSchema()))
            .queryParameter(optionalParam("ticketsPage", numberSchema()))
            .queryParameter(optionalParam("banHistoryPage", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string
        val ticketsPage = parameters.queryParameter("ticketsPage")?.long ?: 1L
        val banHistoryPage = parameters.queryParameter("banHistoryPage")?.long ?: 1L

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(username, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val user = databaseManager.userDao.getByUsername(
            username,
            sqlClient
        )!!

        val result = mutableMapOf<String, Any?>()

        result["player"] = mutableMapOf<String, Any?>(
            "id" to user.id,
            "username" to user.username,
            "email" to user.email,
            "registerDate" to user.registerDate,
            "lastLoginDate" to user.lastLoginDate,
            "isBanned" to BanUtil.isBanned(user),
            "canCreateTicket" to user.canCreateTicket,
            "isEmailVerified" to user.emailVerified,
            "permissionGroup" to "-",
            "lastActivityTime" to user.lastActivityTime,
            "inGame" to databaseManager.serverPlayerDao.existsByUsername(user.username, sqlClient),
            "localeCode" to user.localeCode,
            "registeredIp" to user.registeredIp,
        )

        @Suppress("UNCHECKED_CAST")
        (result["player"] as MutableMap<String, Any?>)["permissionGroup"] = permissionManager.getPermissionGroup(user.id)

        val banHistoryCount = databaseManager.banHistoryDao.countByUserId(user.id, sqlClient)

        var banHistoryTotalPage = ceil(banHistoryCount.toDouble() / 10).toLong()

        if (banHistoryTotalPage < 1)
            banHistoryTotalPage = 1

        if (banHistoryPage !in 1..banHistoryTotalPage) {
            throw PageNotFound()
        }

        result["banHistoryCount"] = banHistoryCount
        result["banHistoryTotalPage"] = banHistoryTotalPage
        result["banHistory"] = databaseManager.banHistoryDao.getAllByUserIdAndPage(user.id, banHistoryPage, sqlClient)

        if (!authProvider.hasPermission(ManageTicketsPermission(), context)) {
            return Successful(result)
        }

        val ticketsCount = databaseManager.ticketDao.countByUserId(user.id, sqlClient)

        var ticketsTotalPage = ceil(ticketsCount.toDouble() / 10).toLong()

        if (ticketsTotalPage < 1)
            ticketsTotalPage = 1

        if (ticketsPage !in 1..ticketsTotalPage) {
            throw PageNotFound()
        }

        result["ticketCount"] = ticketsCount
        result["ticketTotalPage"] = ticketsTotalPage

        if (ticketsCount == 0L) {
            return getTickets(result, listOf(), mapOf(), user.username)
        }

        val tickets = databaseManager.ticketDao.getAllByUserIdAndPage(user.id, ticketsPage, sqlClient)

        val categoryIdList = tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getTickets(result, tickets, mapOf(), username)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlClient)

        return getTickets(result, tickets, ticketCategoryList, username)
    }

    private fun getTickets(
        result: MutableMap<String, Any?>,
        tickets: List<Ticket>,
        ticketCategoryList: Map<Long, TicketCategory>,
        username: String
    ): Result {
        val ticketDataList = mutableListOf<Map<String, Any?>>()

        tickets.forEach { ticket ->
            ticketDataList.add(
                mapOf(
                    "id" to ticket.id,
                    "title" to ticket.title,
                    "category" to
                            if (ticket.categoryId == -1L)
                                mapOf("id" to -1, "title" to "-")
                            else
                                ticketCategoryList.getOrDefault(
                                    ticket.categoryId,
                                    mapOf("id" to -1, "title" to "-")
                                ),
                    "writer" to mapOf(
                        "username" to username
                    ),
                    "date" to ticket.date,
                    "lastUpdate" to ticket.lastUpdate,
                    "status" to ticket.status
                )
            )
        }

        result["tickets"] = ticketDataList

        return Successful(result)
    }
}