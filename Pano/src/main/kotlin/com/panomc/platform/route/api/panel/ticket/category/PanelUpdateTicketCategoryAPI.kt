package com.panomc.platform.route.api.panel.ticket.category


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedTicketCategoryLog
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketCategory
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelUpdateTicketCategoryAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/categories/:id", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("description", stringSchema())
                        .optionalProperty("enabled", booleanSchema())
                        .optionalProperty("autoReplyTemplateId", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val title = data.getString("title")
        val description = data.getString("description")
        val enabled = data.getBoolean("enabled") ?: true

        validateForm(title)

        val sqlClient = getSqlClient()

        val category = databaseManager.ticketCategoryDao.getById(id, sqlClient) ?: throw NotExists()

        // Left out of the request: the category keeps the auto-reply it has.
        val autoReplyTemplateId = data.getLong("autoReplyTemplateId") ?: category.autoReplyTemplateId

        if (autoReplyTemplateId != -1L && databaseManager.ticketTemplateDao.getById(autoReplyTemplateId, sqlClient) == null) {
            throw InvalidFields(mapOf("autoReplyTemplateId" to true))
        }

        databaseManager.ticketCategoryDao.update(TicketCategory(id, title, description, enabled = enabled, autoReplyTemplateId = autoReplyTemplateId), sqlClient)

        val url = TextUtil.convertStringToUrl(title, 32)

        databaseManager.ticketCategoryDao.updateUrlById(id, "$url-$id", sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UpdatedTicketCategoryLog(userId, username, title), sqlClient)

        return Successful()
    }

    private fun validateForm(
        title: String,
//        description: String,
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > 32)
            errors["title"] = true

//        if (description.isEmpty())
//            errors["description"] = true

        if (errors.isNotEmpty()) {
            throw InvalidFields(errors)
        }
    }
}