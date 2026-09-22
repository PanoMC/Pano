package com.panomc.platform.route.api.panel.task

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Current state of one long-running node job.
 *
 * The panel follows a task over the realtime hub; this exists for the page that is opened after
 * the fact, or reloaded while an install is still running.
 */
@Endpoint
class PanelGetTaskAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/tasks/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        // Two audiences, neither of which should need the other's permission: whoever started an
        // install follows it, and whoever looks after the nodes sees everything running on them.
        authProvider.requireAnyPermission(context, CreateServersPermission(), ManageNodesPermission())

        val id = getParameters(context).pathParameter("id").long

        val sqlClient = getSqlClient()

        val task = databaseManager.serverTaskDao.getById(id, sqlClient) ?: throw NotExists()

        return Successful(mapOf("task" to task.toPublicJsonObject()))
    }
}
