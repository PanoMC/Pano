package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PermissionUpdateGroupAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permission/update/group")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val id = data.getInteger("id")
        val name = data.getString("name")

        validateForm(handler, name) {
            databaseManager.createConnection(
                (this::createConnectionHandler)(
                    handler,
                    id,
                    getSystematicName(name)
                )
            )
        }
    }

    private fun validateForm(
        handler: (result: Result) -> Unit,
        name: String,
        successHandler: () -> Unit
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (name.isEmpty() || name.length > 32)
            errors["name"] = true

        if (errors.isNotEmpty()) {
            handler.invoke(Errors(errors))

            return
        }

        successHandler.invoke()
    }

    private fun getSystematicName(name: String) = name.lowercase().replace("\\s+".toRegex(), "-")

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        id: Int,
        name: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.isThereByID(
            id,
            sqlConnection,
            (this::isThereByIDHandler)(sqlConnection, handler, id, name)
        )
    }

    private fun isThereByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        name: String
    ) = handler@{ isTherePermissionGroup: Boolean?, _: AsyncResult<*> ->
        if (isTherePermissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.isThere(
            PermissionGroup(id, name),
            sqlConnection,
            (this::isThereHandler)(sqlConnection, handler, id, name)
        )
    }

    private fun isThereHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        name: String
    ) = handler@{ isTherePermissionGroup: Boolean?, _: AsyncResult<*> ->
        if (isTherePermissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (isTherePermissionGroup) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Errors(mapOf("name" to true)))
            }

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.getPermissionGroupByID(
            id,
            sqlConnection,
            (this::getPermissionGroupByIDHandler)(sqlConnection, handler, id, name)
        )
    }

    private fun getPermissionGroupByIDHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
        id: Int,
        name: String,
    ) = handler@{ permissionGroup: PermissionGroup?, _: AsyncResult<*> ->
        if (permissionGroup == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (permissionGroup.name == "admin") {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.CANT_UPDATE_ADMIN_PERMISSION))
            }

            return@handler
        }

        databaseManager.getDatabase().permissionGroupDao.update(
            PermissionGroup(id, name),
            sqlConnection,
            (this::updateHandler)(sqlConnection, handler)
        )
    }

    private fun updateHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit
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