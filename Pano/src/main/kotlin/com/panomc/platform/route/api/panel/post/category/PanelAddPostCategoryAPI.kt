package com.panomc.platform.route.api.panel.post.category

import com.panomc.platform.ticket.TicketFields
import com.panomc.platform.i18n.ContentI18n
import com.panomc.platform.i18n.ContentTranslations
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.CreatedPostCategoryLog
import com.panomc.platform.auth.panel.permission.ManagePostsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelAddPostCategoryAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val contentTranslations: ContentTranslations
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/post/categories", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("description", stringSchema())
                        .requiredProperty("url", stringSchema())
                        .optionalProperty("color", stringSchema())
                        // the title and description in other languages: { "<locale>": { "title", "description" } }
                        .optionalProperty("i18n", objectSchema().allowAdditionalProperties(true))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePostsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val title = data.getString("title")
        val description = data.getString("description")
        val url = data.getString("url")
        val color = data.getString("color") ?: "#1976d2"

        validateForm(title, url, color)

        val translations = if (data.containsKey("i18n")) {
            try {
                ContentI18n.parseFlat("", data.getValue("i18n"), mapOf("title" to TITLE_I18N_MAX, "description" to ContentTranslations.DESCRIPTION_MAX))
            } catch (e: TicketFields.Invalid) {
                throw InvalidFields(e.errors)
            }
        } else {
            null
        }

        val sqlClient = getSqlClient()

        val exists = databaseManager.postCategoryDao.existsByUrl(url, sqlClient)

        if (exists) {
            val errors = mutableMapOf<String, Boolean>()

            errors["url"] = true

            throw InvalidFields(errors)
        }

        val id = databaseManager.postCategoryDao.add(
            PostCategory(title = title, description = description, url = url, color = color),
            sqlClient
        )

        translations?.let { contentTranslations.replace(ContentI18n.postCategoryPrefix(id), it.mapKeys { entry -> ContentI18n.postCategoryPrefix(id) + entry.key }, sqlClient) }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(CreatedPostCategoryLog(userId, username, title), sqlClient)

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
//        if (color.length != 7) {
//            throw Error(ErrorCode.UNKNOWN)
//        }

        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

//        if (description.isEmpty())
//            errors["description"] = true

        if (url.isEmpty() || url.length < 3 || url.length > 32 || !url.matches(Regex("^[a-zA-Z0-9-]+\$")))
            errors["url"] = true

        if (errors.isNotEmpty()) {
            throw InvalidFields(errors)
        }
    }

    private companion object {
        /** The longest translated title: the default title's limit. */
        const val TITLE_I18N_MAX = 255
    }
}
