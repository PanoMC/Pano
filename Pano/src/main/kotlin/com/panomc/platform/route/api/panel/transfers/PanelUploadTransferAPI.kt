package com.panomc.platform.route.api.panel.transfers

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.model.*
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Parks an uploaded archive until the wizard knows what to do with it.
 *
 * Importing a server by uploading a zip has a chicken-and-egg problem: the upload is a gigabyte
 * that has to cross Pano while the person is still choosing a node and a name, and there is no
 * server to attach it to until they press Create. So the upload is its own step — it returns a
 * ticket, and `servers/create` redeems that ticket once it knows which node will fetch the file.
 *
 * The ticket is bound to the person and nothing else while it waits, which is why the spool
 * direction exists at all: a ticket with a blank node id in the ordinary upload direction would be
 * redeemable by every node that asked. Thirty minutes is the whole lifetime, and the sweep deletes
 * the spooled file with the ticket, so an abandoned wizard costs a temporary file and not a disk.
 *
 * Gated on [CreateServersPermission] rather than a file permission: this is the first step of
 * creating a server, and it is the only thing the ticket can ever be used for.
 */
@Endpoint
class PanelUploadTransferAPI(
    private val vertx: Vertx,
    private val authProvider: AuthProvider,
    private val transferTicketStore: TransferTicketStore
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/transfers/upload", RouteType.POST))

    // Nothing to validate: the body is a multipart file and every other decision is made from the
    // session, so a schema would only be able to say "yes, that is a request".
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(MAX_UPLOAD_BYTES)

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val uploads = context.fileUploads()

        val upload = uploads.firstOrNull { it.name() == PART_NAME }
            ?: uploads.singleOrNull()
            ?: throw BadRequest()

        if (upload.size() > MAX_UPLOAD_BYTES) {
            throw FileTooLarge()
        }

        // The browser names the file and the name is only ever shown back, never used as a path:
        // the spool is named after the ticket, which Pano generated.
        val fileName = File(upload.fileName().orEmpty()).name.take(MAX_NAME_LENGTH).ifEmpty { DEFAULT_NAME }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = 0,
            nodeId = 0,
            serverUuid = "",
            path = "",
            direction = TransferDirection.UPLOAD_SPOOL,
            fileName = fileName,
            ttlMs = TransferTicketStore.SPOOL_TTL_MS
        )

        val spool = transferTicketStore.spoolFileFor(ticket.id)

        ticket.spoolFile = spool

        val size: Long

        try {
            moveSpool(File(upload.uploadedFileName()), spool)

            size = spool.length()
        } catch (exception: Exception) {
            // Nothing else will ever look at this ticket, and the sweep is an hour of disk away.
            transferTicketStore.discard(ticket)

            throw exception
        }

        return Successful(
            mapOf(
                "ticket" to ticket.id,
                "size" to size,
                "filename" to fileName,
                "expiresAt" to ticket.expiresAt
            )
        )
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
        /** The multipart field the panel uploads under, the same one the file manager uses. */
        const val PART_NAME = "file"

        /** The contract's ceiling for one transfer. */
        const val MAX_UPLOAD_BYTES = 1024L * 1024 * 1024

        private const val MAX_NAME_LENGTH = 255
        private const val DEFAULT_NAME = "upload.zip"
    }
}
