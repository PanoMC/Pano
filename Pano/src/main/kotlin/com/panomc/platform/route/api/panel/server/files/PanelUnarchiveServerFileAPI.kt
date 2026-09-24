package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.PathDenied
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileUnarchiveMessage
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

/** Extracts a zip already inside a managed server into a directory of that same server. */
@Endpoint
class PanelUnarchiveServerFileAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/files/unarchive", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("path", stringSchema())
                        .requiredProperty("target", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val data = parameters.body().jsonObject
        val archivePath = ManagedServerFileClient.normalisePath(data.getString("path"))
        val destination = ManagedServerFileClient.normalisePath(data.getString("target"))

        if (archivePath.isEmpty()) {
            throw PathDenied()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        fileClient.request(
            target,
            FileUnarchiveMessage(target.serverUuid, archivePath, destination),
            UNARCHIVE_TIMEOUT_MS
        )

        fileClient.log(context, id, ServerFileChangedLog.ACTION_UNARCHIVE, archivePath, destination, sqlClient)

        panelRealtimeHub.pushServerFilesChanged(id, destination)

        return Successful()
    }

    companion object {
        const val UNARCHIVE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
