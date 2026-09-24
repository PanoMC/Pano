package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileDeleteMessage
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Deletes files and directories from a managed server.
 *
 * Every path is logged individually. A delete is the one file operation that cannot be undone from
 * the panel, so "which twelve things went" is exactly what somebody will want to know later.
 */
@Endpoint
class PanelDeleteServerFilesAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/files/delete", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(json(objectSchema().requiredProperty("paths", arraySchema().items(stringSchema()))))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val requested = parameters.body().jsonObject.getJsonArray("paths")?.mapNotNull { it as? String }

        val paths = ManagedServerFileClient.normalisePaths(requested)

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val payload = fileClient.request(target, FileDeleteMessage(target.serverUuid, paths))

        paths.forEach { path ->
            fileClient.log(context, id, ServerFileChangedLog.ACTION_DELETE, path, sqlClient = sqlClient)
        }

        panelRealtimeHub.pushServerFilesChanged(id, paths.first().substringBeforeLast('/', ""))

        return Successful(mapOf("removed" to payload.getInteger("removed", 0)))
    }
}
