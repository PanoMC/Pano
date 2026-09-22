package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.RenamedNodeLog
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** Renames a node. The name is Pano's label for the host and is never sent to the node itself. */
@Endpoint
class PanelUpdateNodeAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/:id", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("name", stringSchema().withKeyword("maxLength", 255))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val name = parameters.body().jsonObject.getString("name").trim().take(MAX_NAME_LENGTH)

        if (name.isEmpty()) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()

        if (!databaseManager.nodeDao.existsById(id, sqlClient)) {
            throw NotExists()
        }

        databaseManager.nodeDao.updateNameById(id, name, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(RenamedNodeLog(userId, username, id, name), sqlClient)

        panelRealtimeHub.notifyNodeUpdated(id)

        return Successful()
    }

    companion object {
        private const val MAX_NAME_LENGTH = 255
    }
}
