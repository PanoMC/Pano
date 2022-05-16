package com.panomc.platform.route.api.panel.ticket.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class TicketCategoryDeleteAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/category/delete")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val id = data.getInteger("id")

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.ticketCategoryDao.isExistsByID(id, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        databaseManager.ticketCategoryDao.deleteByID(id, sqlConnection)

        return Successful()
    }
}