package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.PathDenied
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.TransferPushMessage
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.console.ServerActionRateLimiter
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Takes a file from the browser and hands it to the node that owns the server.
 *
 * Three hops rather than two, on purpose. Vert.x spools the multipart body to disk as it arrives,
 * this moves that spool under the ticket, and the node then fetches it with an ordinary GET — so a
 * gigabyte crosses Pano as a file on disk and never as a buffer in its heap. The upload is
 * reported as finished only once the node says it wrote the file, because "uploaded" and "arrived
 * in the server directory" are not the same thing and only the second one is useful.
 */
@Endpoint
class PanelUploadServerFileAPI(
    private val vertx: Vertx,
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/files/upload", RouteType.POST))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(MAX_UPLOAD_BYTES)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("path", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        // §2.4.12. An upload spools a whole file before the node ever sees it, so it is the one file action worth limiting far harder than the rest.
        if (!serverActionRateLimiter.tryAcquire(
                ServerActionRateLimiter.Action.UPLOAD,
                authProvider.getUserIdFromRoutingContext(context),
                id
            )
        ) {
            throw RateLimited()
        }

        val directory = ManagedServerFileClient.normalisePath(parameters.queryParameter("path")?.string)

        val uploads = context.fileUploads()

        // The panel sends one part named "file" per request. A single unnamed part is accepted
        // too, because refusing one over a form field's name would be pedantry, but several parts
        // are not: which of them the person meant is not something to guess at.
        val upload = uploads.firstOrNull { it.name() == PART_NAME }
            ?: uploads.singleOrNull()
            ?: throw BadRequest()

        if (upload.size() > MAX_UPLOAD_BYTES) {
            throw FileTooLarge()
        }

        // The browser names the file, so it is a path segment until proven otherwise: a name with
        // a slash in it is how an upload turns into a write somewhere else entirely.
        val name = ManagedServerFileClient.normalisePath(upload.fileName())

        if (name.isEmpty() || name.contains('/')) {
            throw PathDenied()
        }

        val path = if (directory.isEmpty()) name else "$directory/$name"

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = id,
            nodeId = target.nodeId,
            serverUuid = target.serverUuid,
            path = path,
            direction = TransferDirection.UPLOAD,
            fileName = name
        )

        val spool = transferTicketStore.spoolFileFor(ticket.id)

        ticket.spoolFile = spool

        val size: Long

        try {
            moveSpool(File(upload.uploadedFileName()), spool)

            size = spool.length()

            fileClient.request(
                target,
                TransferPushMessage(ticket.id, target.serverUuid, path, size),
                NodeManager.TRANSFER_REQUEST_TIMEOUT_MS
            )
        } finally {
            // Whatever happened, the copy on Pano's disk has done its job: the node either fetched
            // it or never will, and leaving it behind would slowly fill file-uploads with other
            // people's server files.
            transferTicketStore.discard(ticket)
        }

        fileClient.log(context, id, ServerFileChangedLog.ACTION_UPLOAD, path, sqlClient = sqlClient)

        panelRealtimeHub.pushServerFilesChanged(id, directory)

        return Successful(mapOf("path" to path, "size" to size))
    }

    /** Moves the multipart spool under the ticket, falling back to a copy across filesystems. */
    private suspend fun moveSpool(uploaded: File, spool: File) {
        vertx.executeBlocking<Unit> {
            spool.parentFile?.mkdirs()

            try {
                Files.move(uploaded.toPath(), spool.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                uploaded.copyTo(spool, overwrite = true)
            }
        }.coAwait()
    }

    companion object {
        /** The multipart field the panel uploads under. */
        const val PART_NAME = "file"

        /** The contract's ceiling for one transfer. */
        const val MAX_UPLOAD_BYTES = 1024L * 1024 * 1024
    }
}
