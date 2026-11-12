package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.EditedTicketFieldsLog
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.ticket.TicketFields
import com.panomc.platform.util.UsageMode
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `PUT /tickets/:id/fields` `{ fields: { key: value } }`: staff change the custom field values of a ticket. Only the
 * entries of the ticket's snapshot can change; each new value is checked by the entry's type, options and bounds
 * (not `required`, not the conditions). Answers `{ fields: [snapshot] }`.
 */
@Endpoint
class PanelUpdateTicketFieldsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/tickets/:id/fields", RouteType.PUT))

    override val doc = EndpointDoc(
        summary = "Changes the custom field values of a ticket.",
        tag = "tickets",
        errors = listOf(NotExists::class, InvalidFields::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val raw = parameters.body().jsonObject.getValue("fields") as? JsonObject
            ?: throw InvalidFields(mapOf("fields" to "INVALID"))

        val sqlClient = getSqlClient()

        val ticket = databaseManager.ticketDao.getById(id, sqlClient) ?: throw NotExists()

        val (snapshot, changed) = try {
            TicketFields.applyEdit(TicketFields.storedSnapshot(databaseManager.ticketFieldSetDao.getByTicketId(id, sqlClient)), raw)
        } catch (e: TicketFields.Invalid) {
            throw InvalidFields(e.errors)
        }

        if (changed) {
            databaseManager.ticketFieldSetDao.set(id, snapshot.encode(), TicketFields.searchText(snapshot), sqlClient)

            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

            databaseManager.panelActivityLogDao.add(EditedTicketFieldsLog(userId, username, id), sqlClient)

            panelRealtimeHub.notifyTicketChanged(id, ticket.userId)
        }

        return Successful(mapOf("fields" to snapshot))
    }
}
