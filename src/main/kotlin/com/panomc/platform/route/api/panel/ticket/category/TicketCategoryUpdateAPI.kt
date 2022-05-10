package com.panomc.platform.route.api.panel.ticket.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class TicketCategoryUpdateAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/ticket/category/update")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val id = data.getInteger("id")
        val title = data.getString("title")
        val description = data.getString("description")

        validateForm(handler, title) {
            databaseManager.createConnection((this::createConnectionHandler)(handler, id, title, description))
        }
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        id: Int,
        title: String,
        description: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.ticketCategoryDao.update(
            TicketCategory(id, title, description),
            sqlConnection,
            (this::updateHandler)(handler, sqlConnection)
        )
    }

    private fun updateHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }

    private fun validateForm(
        handler: (result: Result) -> Unit,
        title: String,
//        description: String,
        successHandler: () -> Unit
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

//        if (description.isEmpty())
//            errors["description"] = true

        if (errors.isNotEmpty()) {
            handler.invoke(Errors(errors))

            return
        }

        successHandler.invoke()
    }
}