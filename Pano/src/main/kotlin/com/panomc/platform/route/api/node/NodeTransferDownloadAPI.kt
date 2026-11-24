package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.error.NotExists
import com.panomc.platform.node.transfer.StreamRelay
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferRedeemer
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.node.transfer.UploadAborted
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.net.URLEncoder

/**
 * The node half of an upload (`GET /api/v1/node/transfer/:ticket`).
 *
 * Pano keeps nothing: the browser's request is joined to this one and its bytes are written into
 * this response as they arrive, with the node's pace setting the browser's (a full write queue
 * pauses the browser). Whichever request comes first waits for the other at the ticket — an import's
 * node is here before the browser starts, a file-manager upload's browser before its node — until
 * the ticket would have expired.
 *
 * The body is chunked: the browser's multipart framing hides the file's exact length, so the length
 * it announced goes along in [UPLOAD_SIZE_HEADER] as an upper bound for the node's disk check and
 * progress. A transfer that breaks halfway resets this response, which is how the node learns the
 * file it was writing is incomplete and has to go.
 *
 * A small file Pano wrote itself (a server icon) is still sent from disk.
 */
@Endpoint
class NodeTransferDownloadAPI(
    private val transferRedeemer: TransferRedeemer,
    private val transferTicketStore: TransferTicketStore,
    private val logger: Logger
) : Api() {
    override val paths = listOf(Path("/node/transfer/:ticket", RouteType.GET))

    // Authenticates with a node token, never a user JWT.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun bodyHandler(): Handler<RoutingContext>? = null

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val ticketId = context.pathParam("ticket")

        // A node token or a plugin token, settled in one place: the two sides speak the same
        // transfer protocol and each may only redeem its own kind of ticket (§2.4.17 C).
        val ticket = transferRedeemer.redeem(context, ticketId, TransferDirection.UPLOAD)
            ?: return NotExists()

        val response = context.response()

        ticket.spoolFile?.let { spool ->
            if (!spool.isFile) {
                return NotExists()
            }

            response
                .putHeader("Content-Type", "application/octet-stream")
                .putHeader("Content-Length", spool.length().toString())
                .sendFile(spool.absolutePath)
                .coAwait()

            return null
        }

        val upload = ticket.upload ?: return NotExists()

        // Only one node request per upload: a second one (a retry after a dropped connection) would
        // get the rest of a stream the first already took part of.
        if (ticket.started) {
            return NotExists()
        }

        ticket.started = true

        val waitMs = (ticket.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)

        val source = try {
            withTimeoutOrNull(waitMs) { upload.source.await() }
        } catch (exception: Exception) {
            null
        }

        if (source == null) {
            // The browser never came, or left before sending: nothing will arrive under this ticket.
            transferTicketStore.consume(ticket.id)

            upload.abandon(UploadAborted("The upload never started."))

            return NotExists()
        }

        response
            .putHeader("Content-Type", "application/octet-stream")
            .putHeader(FILE_NAME_HEADER, URLEncoder.encode(source.fileName.take(255), Charsets.UTF_8))
            .setChunked(true)

        upload.declaredSize?.let { response.putHeader(UPLOAD_SIZE_HEADER, it.toString()) }

        val relay = StreamRelay.start(context.vertx(), source.stream, response, onChunk = source.inspect)

        // The node went away (disk full, crashed, cancelled): the copy stops, and the browser side
        // learns it from the failed delivery.
        response.closeHandler { relay.fail(UploadAborted("The node closed the transfer.")) }

        try {
            val bytes = relay.future.coAwait()

            response.end().coAwait()

            transferTicketStore.consume(ticket.id)

            upload.delivered.complete(bytes)
        } catch (exception: Throwable) {
            logger.warn("An upload of ${ticket.fileName} to node broke after ${relay.bytes} bytes: ${exception.message}")

            transferTicketStore.consume(ticket.id)

            upload.delivered.completeExceptionally(exception)

            runCatching { response.reset() }
        }

        return null
    }

    companion object {
        /** The upload's name as the browser gave it, URL-encoded, for the node's log and a GZ's output name. */
        const val FILE_NAME_HEADER = "X-Pano-File-Name"

        /** What the browser announced, an upper bound on the file's size. */
        const val UPLOAD_SIZE_HEADER = "X-Pano-Upload-Size"
    }
}
