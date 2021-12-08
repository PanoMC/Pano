package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PostCategoryAddAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/category/add")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson
        val title = data.getString("title")
        val description = data.getString("description")
        val url = data.getString("url")
        val color = data.getString("color")

        validateForm(handler, title, description, url, color) {
            databaseManager.createConnection((this::createConnectionHandler)(handler, title, description, url, color))
        }
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        title: String,
        description: String,
        url: String,
        color: String
    ) =
        handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
            if (sqlConnection == null) {
                handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                return@handler
            }

            databaseManager.getDatabase().postCategoryDao.isExistsByURL(
                url,
                sqlConnection,
                (this::isExistsByURLHandler)(handler, title, description, url, color, sqlConnection)
            )
        }

    private fun isExistsByURLHandler(
        handler: (result: Result) -> Unit,
        title: String,
        description: String,
        url: String,
        color: String,
        sqlConnection: SqlConnection
    ) = handler@{ exists: Boolean?, _: AsyncResult<*> ->
        if (exists == null) {
            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Error(ErrorCode.UNKNOWN))
            }

            return@handler
        }

        if (exists) {
            val errors = mutableMapOf<String, Boolean>()

            errors["url"] = true

            databaseManager.closeConnection(sqlConnection) {
                handler.invoke(Errors(errors))
            }

            return@handler
        }

        databaseManager.getDatabase().postCategoryDao.add(
            PostCategory(-1, title, description, url, color),
            sqlConnection,
            (this::addHandler)(handler, sqlConnection)
        )
    }

    private fun addHandler(
        handler: (result: Result) -> Unit,
        sqlConnection: SqlConnection
    ) = handler@{ id: Long?, _: AsyncResult<*> ->
        databaseManager.closeConnection(sqlConnection) {
            if (id == null) {
                handler.invoke(Error(ErrorCode.UNKNOWN))

                return@closeConnection
            }

            handler.invoke(
                Successful(
                    mapOf(
                        "id" to id
                    )
                )
            )
        }
    }

    private fun validateForm(
        handler: (result: Result) -> Unit,
        title: String,
        description: String,
        url: String,
        color: String,
        successHandler: () -> Unit
    ) {
        if (color.length != 7) {
            handler.invoke(Error(ErrorCode.UNKNOWN))

            return
        }

        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

        if (description.isEmpty())
            errors["description"] = true

        if (url.isEmpty() || url.length < 3 || url.length > 32 || !url.matches(Regex("^[a-zA-Z0-9-]+\$")))
            errors["url"] = true

        if (errors.isNotEmpty()) {
            handler.invoke(Errors(errors))

            return
        }

        successHandler.invoke()
    }
}