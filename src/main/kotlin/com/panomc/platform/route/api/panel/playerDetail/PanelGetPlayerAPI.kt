package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Ticket
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlin.math.ceil

@Endpoint
class PanelGetPlayerAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/players/:username")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .pathParameter(param("username", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string
        val page = parameters.queryParameter("page")?.long ?: 1L

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val user = databaseManager.userDao.getByUsername(
            username,
            sqlConnection
        ) ?: throw Error(ErrorCode.UNKNOWN)

        val result = mutableMapOf<String, Any?>()

        result["player"] = mutableMapOf<String, Any?>(
            "id" to user.id,
            "username" to user.username,
            "email" to user.email,
            "registerDate" to user.registerDate,
            "isBanned" to user.banned,
            "isEmailVerified" to user.emailVerified,
            "permissionGroup" to "-"
        )

        if (user.permissionGroupId != -1L) {
            val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(
                user.permissionGroupId,
                sqlConnection
            ) ?: throw Error(ErrorCode.UNKNOWN)

            @Suppress("UNCHECKED_CAST")
            (result["player"] as MutableMap<String, Any?>)["permissionGroup"] = permissionGroup.name
        }

        val count = databaseManager.ticketDao.countByUserId(user.id, sqlConnection)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw Error(ErrorCode.PAGE_NOT_FOUND)
        }

        result["ticketCount"] = count
        result["ticketTotalPage"] = totalPage

        if (count == 0L) {
            return getTickets(result, listOf(), mapOf(), user.username)
        }

        val tickets = databaseManager.ticketDao.getAllByUserIdAndPage(user.id, page, sqlConnection)

        val categoryIdList = tickets.filter { it.categoryId != -1L }.distinctBy { it.categoryId }.map { it.categoryId }

        if (categoryIdList.isEmpty()) {
            return getTickets(result, tickets, mapOf(), username)
        }

        val ticketCategoryList = databaseManager.ticketCategoryDao.getByIdList(categoryIdList, sqlConnection)

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
                    "status" to ticket.status.value
                )
            )
        }

        result["tickets"] = ticketDataList

        return Successful(result)
    }
}