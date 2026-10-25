package com.panomc.platform.route.api.panel.ticket.template

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.ticket.TicketTemplateRenderer
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder

/**
 * `GET /api/v1/panel/ticket/templates` -- the ready replies by title, one page at a time (`{ items, page }`), with
 * an optional `search` in the title and the text, and the `variables` a template may use.
 */
@Endpoint
class PanelGetTicketTemplatesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/templates", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val page = Paging.request(context)
        val search = getParameters(context).queryParameter("search")?.string?.trim()

        val sqlClient = getSqlClient()

        val count = databaseManager.ticketTemplateDao.count(search, sqlClient)

        Paging.requireInRange(page, count)

        val templates = databaseManager.ticketTemplateDao.getList(search, page.limit, page.offset, sqlClient)

        return Successful(
            Paging.response(
                templates.map {
                    mapOf(
                        "id" to it.id,
                        "title" to it.title,
                        "body" to it.body,
                        "updatedAt" to it.updatedAt
                    )
                },
                count,
                page,
                mapOf("variables" to TicketTemplateRenderer.VARIABLES)
            )
        )
    }
}
