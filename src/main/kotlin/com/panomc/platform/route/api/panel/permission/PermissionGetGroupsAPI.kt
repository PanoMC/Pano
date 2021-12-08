package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PermissionGetGroupsAPI : PanelApi() {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/permission/groups")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        databaseManager.createConnection((this::createConnectionHandler)(handler))
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroups(
            sqlConnection,
            (this::getPermissionGroupsHandler)(sqlConnection, handler)
        )
    }

    private fun getPermissionGroupsHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit
    ) = handler@{ permissionGroups: List<PermissionGroup>?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (permissionGroups == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(Successful(mapOf("permissionGroups" to permissionGroups)))
        }
    }
}