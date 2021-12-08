package com.panomc.platform.route.api.panel.post

import com.panomc.platform.ErrorCode
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PostOnlyPublishAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/onlyPublish")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val id = data.getInteger("id")

        val userID = authProvider.getUserIDFromRoutingContext(context)

        databaseManager.createConnection(
            (this::createConnectionHandler)(
                handler,
                id,
                userID
            )
        )
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        id: Int,
        userID: Int
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().postDao.isExistsByID(
            id,
            sqlConnection,
            (this::isExistsByIDHandler)(handler, sqlConnection, id, userID)
        )
    }

    private fun isExistsByIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        id: Int,
        userID: Int
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (!exists) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.NOT_EXISTS))
            }

            return@handler
        }

        databaseManager.getDatabase().postDao.publishByID(
            id,
            userID,
            sqlConnection,
            (this::publishByIDHandler)(handler, sqlConnection)
        )
    }

    private fun publishByIDHandler(
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
}