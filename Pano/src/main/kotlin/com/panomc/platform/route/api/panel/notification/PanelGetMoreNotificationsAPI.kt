package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelGetMoreNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/notifications/:id/more", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val lastNotificationId = parameters.pathParameter("id").long

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val notifications =
            databaseManager.panelNotificationDao.get10ByUserIdAndStartFromId(userId, lastNotificationId, sqlClient)

        if (!Main.IS_DEMO) {
            databaseManager.panelNotificationDao.markReadLast10StartFromId(userId, lastNotificationId, sqlClient)
        }

        val notificationsDataList = mutableListOf<Map<String, Any?>>()

        notifications.forEach { notification ->
            notificationsDataList.add(
                mapOf(
                    "id" to notification.id,
                    "type" to notification.type.getName(),
                    "details" to notification.details.map,
                    "status" to notification.status.name,
                    "isPersonal" to (notification.userId == userId),
                    "createdAt" to notification.createdAt,
                    "updatedAt" to notification.updatedAt,
                )
            )
        }

        return Successful(
            mutableMapOf(
                "notifications" to notificationsDataList,
            )
        )
    }
}