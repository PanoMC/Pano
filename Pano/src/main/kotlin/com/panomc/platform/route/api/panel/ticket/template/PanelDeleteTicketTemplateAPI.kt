package com.panomc.platform.route.api.panel.ticket.template

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.ticket.TicketSettings
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * `DELETE /api/v1/panel/ticket/templates/:id` -- deletes a ready reply; the categories that sent it as their
 * auto-reply (and tickets without a category) fall back to the default one, and when it was the default, there is no
 * default from then on.
 */
@Endpoint
class PanelDeleteTicketTemplateAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/templates/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        databaseManager.ticketTemplateDao.getById(id, sqlClient) ?: throw NotExists()

        databaseManager.ticketCategoryDao.clearAutoReplyTemplate(id, sqlClient)

        if (TicketSettings.defaultAutoReplyTemplateId(databaseManager, sqlClient) == id) {
            TicketSettings.setDefaultAutoReplyTemplateId(-1, databaseManager, sqlClient)
        }

        if (TicketSettings.noCategoryAutoReplyTemplateId(databaseManager, sqlClient) == id) {
            TicketSettings.setNoCategoryAutoReplyTemplateId(-1, databaseManager, sqlClient)
        }

        databaseManager.ticketTemplateDao.deleteById(id, sqlClient)

        return Successful()
    }
}
