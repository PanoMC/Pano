package com.panomc.platform.route.api.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema

@Endpoint
class MarkQuickNotificationsAsReadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val paths = listOf(Path("/notifications/quick/mark-as-read", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Marks the last five notifications read and answers the unread count.",
        tag = "notifications",
        response = objectSchema().requiredProperty("notificationCount", intSchema())
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        databaseManager.notificationDao.markReadLast5ByUserId(userId, sqlClient)

        val count = databaseManager.notificationDao.getCountOfNotReadByUserId(userId, sqlClient)

        return Successful(
            mutableMapOf(
                "notificationCount" to count
            )
        )
    }
}