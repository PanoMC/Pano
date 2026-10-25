package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.util.UsageMode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.ticket.TicketSettings
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/** Saves what the gear on the panel's Tickets page sets. */
@Endpoint
class PanelUpdateTicketSettingsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/settings", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("allowNoCategory", booleanSchema())
                        .optionalProperty("defaultAutoReplyTemplateId", numberSchema())
                        .optionalProperty("noCategoryAutoReplyTemplateId", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val data = getParameters(context).body().jsonObject
        val allowNoCategory = data.getBoolean("allowNoCategory")
        // Left out of the request: the setting stays as it is.
        val autoReplies = mapOf(
            "defaultAutoReplyTemplateId" to data.getLong("defaultAutoReplyTemplateId"),
            "noCategoryAutoReplyTemplateId" to data.getLong("noCategoryAutoReplyTemplateId")
        )

        val sqlClient = getSqlClient()

        val unknown = autoReplies.filter { (_, templateId) ->
            templateId != null && templateId != -1L && databaseManager.ticketTemplateDao.getById(templateId, sqlClient) == null
        }

        if (unknown.isNotEmpty()) {
            throw InvalidFields(unknown.mapValues { true })
        }

        TicketSettings.setAllowNoCategory(allowNoCategory, databaseManager, sqlClient)

        autoReplies["defaultAutoReplyTemplateId"]?.let {
            TicketSettings.setDefaultAutoReplyTemplateId(it, databaseManager, sqlClient)
        }

        autoReplies["noCategoryAutoReplyTemplateId"]?.let {
            TicketSettings.setNoCategoryAutoReplyTemplateId(it, databaseManager, sqlClient)
        }

        return Successful()
    }
}
