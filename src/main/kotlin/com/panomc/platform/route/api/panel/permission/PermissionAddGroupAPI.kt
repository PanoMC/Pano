package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PermissionAddGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/permission/add/group")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val name = data.getString("name")

        validateForm(handler, name) {
            databaseManager.createConnection(
                (this::createConnectionHandler)(
                    handler,
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
        name: String
    ) = handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
        if (sqlConnection == null) {
            handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

            return@handler
        }

        databaseManager.permissionGroupDao.isThere(
            PermissionGroup(-1, name),
            sqlConnection,
            (this::isThereHandler)(sqlConnection, handler, name)
        )
    }

    private fun isThereHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit,
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

        databaseManager.permissionGroupDao.add(
            PermissionGroup(-1, name),
            sqlConnection,
            (this::addHandler)(sqlConnection, handler)
        )
    }

    private fun addHandler(
        sqlConnection: SqlConnection,
        handler: (result: Result) -> Unit
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (result == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@closeConnection
            }

            handler.invoke(Successful())
        }
    }
}