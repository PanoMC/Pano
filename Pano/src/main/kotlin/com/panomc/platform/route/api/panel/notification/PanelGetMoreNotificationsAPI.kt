package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationTypeRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelGetMoreNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val notificationTypeRegistry: NotificationTypeRegistry
) : PanelApi() {
    override val paths = listOf(Path("/notifications/:id/more", RouteType.GET))

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

        // A full page may be followed by an older one: ask for it, so the last page has no cursor.
        val hasMore = notifications.size >= PanelNotificationPage.SIZE &&
                databaseManager.panelNotificationDao
                    .get10ByUserIdAndStartFromId(userId, notifications.last().id, sqlClient)
                    .isNotEmpty()

        if (!Main.IS_DEMO) {
            databaseManager.panelNotificationDao.markReadLast10StartFromId(userId, lastNotificationId, sqlClient)
        }

        return Successful(
            PanelNotificationPage.payload(
                notifications,
                userId,
                notificationTypeRegistry::ownerOf,
                PanelNotificationPage.SIZE,
                hasMore,
                // After this page was marked read: what the navbar's badge should say now.
                mapOf("notReadCount" to databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlClient))
            )
        )
    }
}
