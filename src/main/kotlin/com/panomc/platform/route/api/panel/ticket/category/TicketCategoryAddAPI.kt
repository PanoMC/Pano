package com.panomc.platform.route.api.panel.ticket.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class TicketCategoryAddAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/category/add")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val title = data.getString("title")
        val description = data.getString("description")

        validateForm(title)

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.ticketCategoryDao.add(TicketCategory(-1, title, description), sqlConnection)

        return Successful()
    }

    private fun validateForm(
        title: String,
//        description: String,
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

//        if (description.isEmpty())
//            errors["description"] = true

        if (errors.isNotEmpty()) {
            throw Errors(errors)
        }
    }
}