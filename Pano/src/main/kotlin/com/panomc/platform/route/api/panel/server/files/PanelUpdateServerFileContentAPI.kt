package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileWriteMessage
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.console.ServerActionRateLimiter
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

/** Saves an edited text file back into a managed server. */
@Endpoint
class PanelUpdateServerFileContentAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/files/content", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("path", stringSchema())
                        .requiredProperty("content", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        // §2.4.12. Every write crosses the node socket and hits a disk on somebody else's machine.
        if (!serverActionRateLimiter.tryAcquire(
                ServerActionRateLimiter.Action.FILE_WRITE,
                authProvider.getUserIdFromRoutingContext(context),
                id
            )
        ) {
            throw RateLimited()
        }

        val data = parameters.body().jsonObject
        val path = ManagedServerFileClient.normalisePath(data.getString("path"))
        val content = data.getString("content") ?: ""

        // Checked here as well as on the node: a megabyte of JSON should be refused before it is
        // encrypted and pushed down a socket, not after.
        if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES) {
            throw FileTooLarge()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        fileClient.request(target, FileWriteMessage(target.serverUuid, path, content))

        fileClient.log(context, id, ServerFileChangedLog.ACTION_WRITE, path, sqlClient = sqlClient)

        panelRealtimeHub.pushServerFilesChanged(id, path.substringBeforeLast('/', ""))

        return Successful()
    }

    companion object {
        const val MAX_CONTENT_BYTES = 1024 * 1024
    }
}
