package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

@Endpoint
class CategoriesAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/post/category/categories")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        databaseManager.createConnection((this::createConnectionHandler)(handler))
    }

    private fun createConnectionHandler(handler: (result: Result) -> Unit) =
        handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
            if (sqlConnection == null) {
                handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                return@handler
            }

            databaseManager.postCategoryDao.getCount(
                sqlConnection,
                (this::getCountHandler)(handler, sqlConnection)
            )
        }

    private fun getCountHandler(handler: (result: Result) -> Unit, sqlConnection: SqlConnection) =
        handler@{ count: Int?, _: AsyncResult<*> ->
            if (count == null) {
                databaseManager.closeConnection(sqlConnection) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))
                }

                return@handler
            }

            databaseManager.postCategoryDao.getAll(
                sqlConnection,
                (this::getCategoriesHandler)(handler, sqlConnection, count)
            )
        }

    private fun getCategoriesHandler(handler: (result: Result) -> Unit, sqlConnection: SqlConnection, count: Int) =
        handler@{ categories: List<PostCategory>?, _: AsyncResult<*> ->
            databaseManager.closeConnection(sqlConnection) {
                if (categories == null) {
                    handler.invoke(Error(ErrorCode.UNKNOWN))

                    return@closeConnection
                }

                val result = mutableMapOf<String, Any?>(
                    "categories" to categories,
                    "categoryCount" to count
                )

                handler.invoke(Successful(result))
            }
        }
}