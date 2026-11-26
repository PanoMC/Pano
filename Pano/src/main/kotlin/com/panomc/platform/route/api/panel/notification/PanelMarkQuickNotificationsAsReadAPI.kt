package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class PanelMarkQuickNotificationsAsReadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/notifications/quick/markAsRead", RouteType.POST))

    // `ids`: the quick notifications the menu shows. Without it (an older panel) the last five are read.
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().optionalProperty("ids", arraySchema().items(intSchema()))))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val ids = context.body().asJsonObject()?.getJsonArray("ids")
            ?.map { (it as Number).toLong() }
            ?.take(QUICK_SIZE)

        // Only the ones the menu showed: one that arrived after the menu last loaded stays unread.
        if (ids != null) {
            databaseManager.panelNotificationDao.markReadByIds(userId, ids, sqlClient)
        } else {
            databaseManager.panelNotificationDao.markReadLast5ByUserId(userId, sqlClient)
        }

        val count = databaseManager.panelNotificationDao.getCountOfNotReadByUserId(userId, sqlClient)

        return Successful(
            mutableMapOf(
                "notificationCount" to count
            )
        )
    }

    private companion object {
        const val QUICK_SIZE = 5
    }
}