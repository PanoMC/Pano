package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.ssh.NodeSshBootstrapService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Accepts a host key and lets the install run
 * (`POST /api/panel/nodes/ssh-bootstrap/:taskId/confirm`).
 *
 * Only the person who started the bootstrap can confirm it, because they are the only one who was
 * shown the fingerprint; another admin confirming a fingerprint they never saw would turn the
 * check into a formality.
 *
 * Returns immediately. The install takes minutes and reports on the task stream like every other
 * long job, and the node appearing in the list is what says it worked.
 */
@Endpoint
class PanelConfirmSshBootstrapNodeAPI(
    private val authProvider: AuthProvider,
    private val nodeSshBootstrapService: NodeSshBootstrapService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/ssh-bootstrap/:taskId/confirm", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("taskId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val taskId = getParameters(context).pathParameter("taskId").string
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val pending = nodeSshBootstrapService.pendingFor(taskId, userId) ?: throw NotExists()

        nodeSshBootstrapService.confirm(pending)

        return Successful(
            mapOf(
                "taskId" to pending.taskId,
                "taskUuid" to pending.taskUuid
            )
        )
    }
}
