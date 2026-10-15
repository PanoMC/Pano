package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.PathDenied
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
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.kotlin.coroutines.coAwait
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.panomc.platform.util.UsageMode

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
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/servers/:id/files/upload", RouteType.POST))

    // No size limit: a world or a backup is as big as it is. That is only safe because nothing is spooled
    // before the caller is known to be allowed to send it ([checkBeforeBody]).
    override fun bodyHandler(): Handler<RoutingContext> = authorizedBodyHandler(
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(-1)
    )

    /**
     * Asked before a byte of the upload is taken: may this person write to this server's files, and is the
     * upload within the limit (§2.4.12). A refusal says how long to wait: the panel's upload queue waits that
     * long and sends the file again.
     */
    override suspend fun checkBeforeBody(context: RoutingContext) {
        val id = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val uploaderId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.FILE_UPLOAD, uploaderId, id)) {
            throw RateLimited(
                extras = mapOf(
                    "retryAfter" to serverActionRateLimiter.retryAfterSeconds(
                        ServerActionRateLimiter.Action.FILE_UPLOAD,
                        uploaderId,
                        id
                    )
                )
            )
        }
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(optionalParam("path", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val directory = ManagedServerFileClient.normalisePath(parameters.queryParameter("path")?.string)

        val uploads = context.fileUploads()

        // The panel sends one part named "file" per request. A single unnamed part is accepted
        // too, because refusing one over a form field's name would be pedantry, but several parts
        // are not: which of them the person meant is not something to guess at.
        val upload = uploads.firstOrNull { it.name() == PART_NAME }
            ?: uploads.singleOrNull()
            ?: throw BadRequest()

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
                transferTimeoutMs(size)
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

        /** The slowest link a transfer to the node is still waited for: 256 KB a second. */
        private const val SLOWEST_BYTES_PER_SECOND = 256L * 1024

        /**
         * How long the node gets to fetch a file of [size] bytes: the usual wait, and for a big file as long
         * as the slowest link worth waiting for would take. Without it a file of a few GB ran into the fixed
         * ten minutes on any ordinary connection.
         */
        internal fun transferTimeoutMs(size: Long): Long =
            maxOf(NodeManager.TRANSFER_REQUEST_TIMEOUT_MS, size / SLOWEST_BYTES_PER_SECOND * 1000L)
    }
}
