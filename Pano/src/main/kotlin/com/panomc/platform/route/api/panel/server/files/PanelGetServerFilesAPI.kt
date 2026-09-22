package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileListMessage
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Lists one directory of a managed server.
 *
 * Nothing is cached and nothing is stored: the node's filesystem is the only source of truth for
 * what is in a server directory, and a server can be writing to it while this runs.
 */
@Endpoint
class PanelGetServerFilesAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/files", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("path", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val path = ManagedServerFileClient.normalisePath(parameters.queryParameter("path")?.string)

        val target = fileClient.resolve(id, getSqlClient())

        val payload = fileClient.request(target, FileListMessage(target.serverUuid, path))

        return Successful(
            mapOf(
                "path" to path,
                "entries" to payload.getJsonArray("entries"),
                "truncated" to payload.getBoolean("truncated", false)
            )
        )
    }
}
