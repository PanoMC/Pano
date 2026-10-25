package com.panomc.platform.route.api.panel.ticket.template

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.ticket.TicketTemplateRenderer
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * `POST /api/v1/panel/tickets/:id/templates/:templateId/render` -- a ready reply filled in for this ticket, with the
 * asking admin as `{{staff}}`: `{ message }`, the HTML the reply editor takes. Nothing is sent.
 */
@Endpoint
class PanelRenderTicketTemplateAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/tickets/:id/templates/:templateId/render", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("templateId", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val ticketId = parameters.pathParameter("id").long
        val templateId = parameters.pathParameter("templateId").long

        val sqlClient = getSqlClient()

        val ticket = databaseManager.ticketDao.getById(ticketId, sqlClient) ?: throw NotExists()
        val template = databaseManager.ticketTemplateDao.getById(templateId, sqlClient) ?: throw NotExists()

        val staff = databaseManager.userDao.getUsernameFromUserId(
            authProvider.getUserIdFromRoutingContext(context),
            sqlClient
        )

        val values = TicketTemplateRenderer.values(ticket, staff, databaseManager, configManager, sqlClient)

        return Successful(mapOf("message" to TicketTemplateRenderer.render(template.body, values)))
    }
}
