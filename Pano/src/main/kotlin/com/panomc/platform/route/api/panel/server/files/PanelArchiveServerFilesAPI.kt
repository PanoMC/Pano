package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.PathDenied
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileArchiveMessage
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

/**
 * Zips a selection inside a managed server.
 *
 * The archive is written on the node rather than streamed through Pano: a hundred megabytes of
 * world would otherwise cross the socket twice for no reason, and the panel only ever wanted a
 * file in the directory it was already looking at.
 */
@Endpoint
class PanelArchiveServerFilesAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/files/archive", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("paths", arraySchema().items(stringSchema()))
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
        val requested = data.getJsonArray("paths")?.mapNotNull { it as? String }

        val sources = ManagedServerFileClient.normalisePaths(requested)
        val archivePath = ManagedServerFileClient.normalisePath(data.getString("target"))

        if (archivePath.isEmpty()) {
            throw PathDenied()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val payload = fileClient.request(
            target,
            FileArchiveMessage(target.serverUuid, sources, archivePath),
            ARCHIVE_TIMEOUT_MS
        )

        fileClient.log(
            context,
            id,
            ServerFileChangedLog.ACTION_ARCHIVE,
            sources.joinToString(", "),
            archivePath,
            sqlClient
        )

        panelRealtimeHub.pushServerFilesChanged(id, archivePath.substringBeforeLast('/', ""))

        return Successful(mapOf("size" to payload.getLong("archiveSize", 0L)))
    }

    companion object {
        /** Zipping a world is minutes of disk, not the thirty seconds a directory listing gets. */
        const val ARCHIVE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
