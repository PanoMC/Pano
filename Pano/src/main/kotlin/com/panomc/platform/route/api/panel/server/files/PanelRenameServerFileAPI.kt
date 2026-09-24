package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.PathDenied
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileRenameMessage
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
import com.panomc.platform.util.UsageMode

/** Renames or moves one path inside a managed server. */
@Endpoint
class PanelRenameServerFileAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/files/rename", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("from", stringSchema())
                        .requiredProperty("to", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val data = parameters.body().jsonObject
        val from = ManagedServerFileClient.normalisePath(data.getString("from"))
        val to = ManagedServerFileClient.normalisePath(data.getString("to"))

        // The server directory itself is not a thing that can be renamed, and an empty source
        // would ask the node to move it.
        if (from.isEmpty() || to.isEmpty()) {
            throw PathDenied()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        fileClient.request(target, FileRenameMessage(target.serverUuid, from, to))

        fileClient.log(context, id, ServerFileChangedLog.ACTION_RENAME, from, to, sqlClient)

        panelRealtimeHub.pushServerFilesChanged(id, to.substringBeforeLast('/', ""))

        return Successful()
    }
}
