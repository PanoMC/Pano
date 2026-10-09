package com.panomc.platform.route.api.notification

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class DeleteNotificationAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val paths = listOf(Path("/notifications/:id", RouteType.DELETE))

    override val doc = EndpointDoc(
        summary = "Deletes one notification of the signed-in user; an unknown or foreign id is ignored.",
        tag = "notifications",
        response = objectSchema()
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val exists = databaseManager.notificationDao.existsById(id, sqlClient)

        if (!exists) {
            return Successful()
        }

        val notification =
            databaseManager.notificationDao.getById(id, sqlClient)!!

        if (notification.userId != userId) {
            return Successful()
        }

        databaseManager.notificationDao.deleteById(notification.id, sqlClient)

        return Successful()
    }
}