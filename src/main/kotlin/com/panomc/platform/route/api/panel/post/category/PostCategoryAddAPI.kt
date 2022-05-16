package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class PostCategoryAddAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/panel/post/category/add")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson
        val title = data.getString("title")
        val description = data.getString("description")
        val url = data.getString("url")
        val color = data.getString("color")

        validateForm(title, url, color)

        val sqlConnection = createConnection(databaseManager, context)

        val exists = databaseManager.postCategoryDao.isExistsByURL(url, sqlConnection)

        if (exists) {
            val errors = mutableMapOf<String, Boolean>()

            errors["url"] = true

            throw Errors(errors)
        }

        val id = databaseManager.postCategoryDao.add(
            PostCategory(-1, title, description, url, color),
            sqlConnection
        )

        return Successful(
            mapOf(
                "id" to id
            )
        )
    }

    private fun validateForm(
        title: String,
//        description: String,
        url: String,
        color: String
    ) {
        if (color.length != 7) {
            throw Error(ErrorCode.UNKNOWN)
        }

        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

//        if (description.isEmpty())
//            errors["description"] = true

        if (url.isEmpty() || url.length < 3 || url.length > 32 || !url.matches(Regex("^[a-zA-Z0-9-]+\$")))
            errors["url"] = true

        if (errors.isNotEmpty()) {
            throw Errors(errors)
        }
    }
}