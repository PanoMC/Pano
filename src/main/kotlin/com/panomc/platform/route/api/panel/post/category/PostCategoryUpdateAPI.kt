package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import io.vertx.core.AsyncResult
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlConnection

class PostCategoryUpdateAPI : PanelApi() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/category/update")

    override fun handler(context: RoutingContext, handler: (result: Result) -> Unit) {
        val data = context.bodyAsJson

        val id = data.getInteger("id")
        val title = data.getString("title")
        val description = data.getString("description")
        val url = data.getString("url")
        val color = data.getString("color")

        validateForm(handler, title, url, color) {
            databaseManager.createConnection(
                (this::createConnectionHandler)(
                    handler,
                    title,
                    description,
                    url,
                    color,
                    id
                )
            )
        }
    }

    private fun createConnectionHandler(
        handler: (result: Result) -> Unit,
        title: String,
        description: String,
        url: String,
        color: String,
        id: Int
    ) =
        handler@{ sqlConnection: SqlConnection?, _: AsyncResult<SqlConnection> ->
            if (sqlConnection == null) {
                handler.invoke(Error(ErrorCode.CANT_CONNECT_DATABASE))

                return@handler
            }

            databaseManager.getDatabase().postCategoryDao.isExistsByURLNotByID(
                url,
                id,
                sqlConnection,
                (this::isExistsByURLNotByID)(handler, title, description, url, color, id, sqlConnection)
            )
        }

    private fun isExistsByURLNotByID(
        handler: (result: Result) -> Unit,
        title: String,
        description: String,
        url: String,
        color: String,
        id: Int,
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

        databaseManager.getDatabase().postCategoryDao.update(
            PostCategory(id, title, description, url, color),
            sqlConnection,
            (this::updateHandler)(handler, sqlConnection)
        )
    }

    private fun updateHandler(handler: (result: Result) -> Unit, sqlConnection: SqlConnection) =
        handler@{ result: Result?, _: AsyncResult<*> ->
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

//        if (description.isEmpty())
//            errors["description"] = true

        if (url.isEmpty() || url.length < 3 || url.length > 32 || !url.matches(Regex("^[a-zA-Z0-9-]+\$")))
            errors["url"] = true

        if (errors.isNotEmpty()) {
            handler.invoke(Errors(errors))

            return
        }

        successHandler.invoke()
    }
}