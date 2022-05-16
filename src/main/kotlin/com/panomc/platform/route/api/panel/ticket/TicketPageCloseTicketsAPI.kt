package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class TicketPageCloseTicketsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/close/selectedList")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val selectedTickets = data.getJsonArray("tickets")

        if (selectedTickets.isEmpty) {
            return Successful()
        }

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.ticketDao.closeTickets(selectedTickets, sqlConnection)

        return Successful()
    }
}