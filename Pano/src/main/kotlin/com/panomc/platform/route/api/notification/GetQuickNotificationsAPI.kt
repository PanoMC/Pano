package com.panomc.platform.route.api.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationTypeRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc

@Endpoint
class GetQuickNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val notificationTypeRegistry: NotificationTypeRegistry
) : LoggedInApi() {
    override val paths = listOf(Path("/notifications/quick", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The last five notifications and the number of unread ones, for a dropdown.",
        tag = "notifications",
        response = NotificationPage.schema
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val notifications = databaseManager.notificationDao.getLast5ByUserId(userId, sqlClient)

        val count = databaseManager.notificationDao.getCountOfNotReadByUserId(userId, sqlClient)

        val total = databaseManager.notificationDao.getCountByUserId(userId, sqlClient)

        return Successful(
            NotificationPage.payload(
                notifications,
                userId,
                notificationTypeRegistry::ownerOf,
                NotificationPage.QUICK_SIZE,
                total > notifications.size,
                count
            )
        )
    }
}
