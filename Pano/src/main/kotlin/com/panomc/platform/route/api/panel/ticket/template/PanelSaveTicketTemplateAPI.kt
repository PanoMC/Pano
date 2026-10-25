package com.panomc.platform.route.api.panel.ticket.template

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.TicketTemplate
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * `POST /api/v1/panel/ticket/templates` adds a ready reply and answers its `id`;
 * `PUT /api/v1/panel/ticket/templates/:id` changes one.
 */
@Endpoint
class PanelSaveTicketTemplateAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(
        Path("/ticket/templates", RouteType.POST),
        Path("/ticket/templates/:id", RouteType.PUT)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(optionalParam("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("title", stringSchema())
                        .requiredProperty("body", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id")?.long
        val title = data.getString("title").trim()
        val body = data.getString("body")

        val errors = mutableMapOf<String, Boolean>()

        if (title.isEmpty() || title.length > TITLE_MAX_LENGTH) {
            errors["title"] = true
        }

        if (body.replace(Regex("<[^>]*>"), "").isBlank()) {
            errors["body"] = true
        }

        if (errors.isNotEmpty()) {
            throw InvalidFields(errors)
        }

        val sqlClient = getSqlClient()

        if (id == null) {
            val newId = databaseManager.ticketTemplateDao.add(TicketTemplate(title = title, body = body), sqlClient)

            return Successful(mapOf("id" to newId))
        }

        val template = databaseManager.ticketTemplateDao.getById(id, sqlClient) ?: throw NotExists()

        databaseManager.ticketTemplateDao.update(
            template.copy(title = title, body = body, updatedAt = System.currentTimeMillis()),
            sqlClient
        )

        return Successful(mapOf("id" to id))
    }

    companion object {
        const val TITLE_MAX_LENGTH = 64
    }
}
