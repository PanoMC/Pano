package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileReadMessage
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Reads a text file out of a managed server, for the editor.
 *
 * Capped rather than streamed: this is the endpoint behind a code editor, and a file that does not
 * fit in one comes back `truncated` so the panel can refuse to save over the part it never saw.
 * `binary` is what stops a jar being rendered as mojibake and then written back.
 */
@Endpoint
class PanelGetServerFileContentAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/files/content", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("path", stringSchema()))
            .queryParameter(optionalParam("maxBytes", intSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val path = ManagedServerFileClient.normalisePath(parameters.queryParameter("path")?.string)
        val maxBytes = (parameters.queryParameter("maxBytes")?.integer ?: DEFAULT_MAX_BYTES)
            .coerceIn(1, MAX_BYTES)

        val target = fileClient.resolve(id, getSqlClient())

        val payload = fileClient.request(target, FileReadMessage(target.serverUuid, path, maxBytes))

        return Successful(
            mapOf(
                "path" to path,
                "content" to payload.getString("content", ""),
                "size" to payload.getLong("size", 0L),
                "truncated" to payload.getBoolean("truncated", false),
                "binary" to payload.getBoolean("binary", false)
            )
        )
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 262_144
        const val MAX_BYTES = 1024 * 1024
    }
}
