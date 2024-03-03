package com.panomc.platform.route.api.panel.player


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlin.math.ceil

@Endpoint
class PanelGetPlayerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("username", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string
        val page = parameters.queryParameter("page")?.long ?: 1L

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
            "isBanned" to user.banned,
            "canCreateTicket" to user.canCreateTicket,
            "isEmailVerified" to user.emailVerified,
            "permissionGroup" to "-",
            "lastActivityTime" to user.lastActivityTime,
            "inGame" to databaseManager.serverPlayerDao.existsByUsername(user.username, sqlClient)
        )

        if (user.permissionGroupId != -1L) {
            val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(
                user.permissionGroupId,
                sqlClient
            )!!

            @Suppress("UNCHECKED_CAST")
            (result["player"] as MutableMap<String, Any?>)["permissionGroup"] = permissionGroup.name
        }

        if (!authProvider.hasPermission(user.id, PanelPermission.MANAGE_TICKETS, context)) {
            return Successful(result)
        }

        val count = databaseManager.ticketDao.countByUserId(user.id, sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        result["ticketCount"] = count
        result["ticketTotalPage"] = totalPage

        if (count == 0L) {
            return getTickets(result, listOf(), mapOf(), user.username)
        }

        val tickets = databaseManager.ticketDao.getAllByUserIdAndPage(user.id, page, sqlClient)

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