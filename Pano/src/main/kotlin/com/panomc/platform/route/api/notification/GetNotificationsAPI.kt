package com.panomc.platform.route.api.notification

import com.panomc.platform.Main
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Notification
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationTypeRegistry
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetNotificationsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val notificationTypeRegistry: NotificationTypeRegistry
) : LoggedInApi() {
    override val paths = listOf(Path("/notifications", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The signed-in user's notifications, newest first; marks the page read.",
        tag = "notifications",
        paginatedItem = CoreSchemas.notification,
        errors = listOf(InvalidFields::class, PageNotFound::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    override suspend fun handle(context: RoutingContext): Result {
        val page = Paging.request(context, DEFAULT_PAGE_SIZE)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val count = databaseManager.notificationDao.getCountByUserId(userId, sqlClient)

        Paging.requireInRange(page, count)

        val notifications =
            databaseManager.notificationDao.getListByUserId(userId, page.limit, page.offset, sqlClient)

        if (!Main.IS_DEMO) {
            databaseManager.notificationDao.markReadByIds(userId, notifications.map { it.id }, sqlClient)
        }

        return Successful(
            payload(notifications, userId, notificationTypeRegistry::ownerOf, count, page)
        )
    }

    companion object {
        /** Notifications per page when the client sends no `pageSize` (as before the page shape). */
        const val DEFAULT_PAGE_SIZE = 10

        /** The whole response body: `{ items, page }`. */
        fun payload(
            notifications: List<Notification>,
            userId: Long,
            ownerOf: (String) -> String?,
            count: Long,
            page: PageRequest
        ): Map<String, Any?> {
            val items = notifications.map { notification ->
                mapOf(
                    "id" to notification.id,
                    "type" to notification.type.getName(),
                    "pluginId" to ownerOf(notification.type.getName()),
                    "details" to notification.details.map,
                    "status" to notification.status,
                    "isPersonal" to (notification.userId == userId),
                    "createdAt" to notification.createdAt,
                    "updatedAt" to notification.updatedAt,
                )
            }

            return Paging.response(items, count, page)
        }
    }
}
