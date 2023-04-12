package com.panomc.platform.route.api.ticket

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class UpdateTicketAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val notificationManager: NotificationManager
) : LoggedInApi() {
    override val paths = listOf(Path("/api/tickets/:id", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("id", numberSchema()))
            .body(
                Bodies.json(
                    objectSchema()
                        .optionalProperty("status", stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val ticketStatus = data.getString("status")
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val exists = databaseManager.ticketDao.existsById(id, sqlClient)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val isBelong = databaseManager.ticketDao.isIdBelongToUserId(id, userId, sqlClient)

        if (!isBelong) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        if (ticketStatus != null && ticketStatus == "close") {
            databaseManager.ticketDao.closeTicketById(id, sqlClient)

            val notificationProperties = JsonObject().put("id", id)

            notificationManager.sendNotificationToAllWithPermission(
                Notifications.PanelNotificationType.TICKET_CLOSED_BY_USER,
                notificationProperties,
                PanelPermission.MANAGE_TICKETS,
                sqlClient
            )
        }

        return Successful()
    }
}