package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PostCategoryDeleteAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/category/delete")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val id = data.getInteger("id")

        databaseManager.createConnection((this::createConnectionHandler)(handler, id))
    }

    private fun createConnectionHandler(handler: (result: Result) -> Unit, id: Int) =
        handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
            if (sqlConnection == null) {
                handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                return@handler
            }

            databaseManager.postCategoryDao.isExistsByID(
                id,
                sqlConnection,
                (this::isExistsByID)(handler, sqlConnection, id)
            )
        }

    private fun isExistsByID(handler: (result: Result) -> Unit, sqlConnection: SqlConnection, id: Int) =
        handler@{ exists: Boolean?, _: AsyncResult<*> ->

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
            }

            databaseManager.postDao.removePostCategoriesByCategoryID(
                id,
                sqlConnection,
                (this::removePostCategoriesByCategoryIDHandler)(handler, sqlConnection, id)
            )
        }

    private fun removePostCategoriesByCategoryIDHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection,
        id: Int
    ) = handler@{ result: Result?, _: AsyncResult<*> ->
        if (result == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        databaseManager.postCategoryDao.deleteByID(
            id,
            sqlConnection,
            (this::deleteByIDHandler)(handler, sqlConnection)
        )
    }

    private fun deleteByIDHandler(handler: (result: Result) -> Unit, sqlConnection: SqlConnection) =
        handler@{ result: Result?, _: AsyncResult<*> ->
            databaseManager.closeConnection(sqlConnection) {
                if (result == null) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))

                    return@closeConnection
                }

                handler.invoke(Successful())
            }
        }
}