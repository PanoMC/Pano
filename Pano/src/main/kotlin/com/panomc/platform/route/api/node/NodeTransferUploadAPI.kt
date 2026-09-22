package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferRedeemer
import com.panomc.platform.node.transfer.TransferTicketStore
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.Logger

/**
 * The node half of a download (`PUT /api/node/transfer/:ticket`).
 *
 * The node opens this with the file as the request body, and the body is piped directly into the
 * browser response that has been parked on this ticket since the person clicked download. Nothing
 * is buffered and nothing is written to Pano's disk — the two requests are simply joined together
 * for as long as the file takes.
 *
 * There is no body handler on purpose. The default one would read the whole thing into memory
 * first, which is the exact problem tickets exist to avoid.
 */
@Endpoint
class NodeTransferUploadAPI(
    private val transferRedeemer: TransferRedeemer,
    private val transferTicketStore: TransferTicketStore,
    private val logger: Logger
) : Api() {
    override val paths = listOf(Path("/api/node/transfer/:ticket", RouteType.PUT))

    // Authenticates with a node token, never a user JWT.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    // Streamed, so the body must not be collected before this handler runs.
    override fun bodyHandler(): Handler<RoutingContext>? = null

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val request = context.request()

        request.pause()

        val ticketId = context.pathParam("ticket")

        // A node token or a plugin token, settled in one place: the two sides speak the same
        // transfer protocol and each may only redeem its own kind of ticket (§2.4.17 C).
        val ticket = transferRedeemer.redeem(context, ticketId, TransferDirection.DOWNLOAD)
            ?: return NotExists()

        val browserResponse = ticket.browserResponse ?: return NotExists()

        // The node could not read the file. Told here rather than over the socket because this is
        // the request the browser is blocked on.
        val error = request.getHeader(ERROR_HEADER)

        if (!error.isNullOrBlank()) {
            ticket.completion.completeExceptionally(IllegalStateException(error))

            return Successful()
        }

        if (browserResponse.ended() || browserResponse.closed()) {
            ticket.completion.completeExceptionally(IllegalStateException("The download was cancelled."))

            return NotExists()
        }

        // Pano's own name wins: a backup is `<uuid>.zip` on disk and "Before the update.zip" to
        // the person downloading it, and only this side knows the second one.
        val fileName = ticket.fileName.takeIf { it.isNotBlank() }
            ?: request.getHeader(FILE_NAME_HEADER)?.takeIf { it.isNotBlank() }
            ?: "download"

        // The type is Pano's to decide, never the source's: a zip it asked for, a preview type from
        // its own allowlist, or opaque bytes.
        browserResponse
            .putHeader("Content-Type", ticket.contentType ?: "application/octet-stream")
            .putHeader("X-Content-Type-Options", "nosniff")

        if (ticket.inline) {
            // A preview renders on the panel's own origin, so it is sandboxed as well: the
            // allowlist already keeps anything scriptable out, and this is what still holds if a
            // browser ever decides an "image" is something else.
            browserResponse
                .putHeader("Content-Disposition", "inline; filename=\"${sanitise(fileName)}\"")
                .putHeader("Content-Security-Policy", "sandbox")
        } else {
            browserResponse
                .putHeader("Content-Disposition", "attachment; filename=\"${sanitise(fileName)}\"")
        }

        // Whatever the node's request carries is what the browser gets, length for length: with a
        // content length the response is not chunked, without one it is.
        val length = request.getHeader("Content-Length")

        if (length != null) {
            browserResponse.putHeader("Content-Length", length)
        } else {
            browserResponse.isChunked = true
        }

        request.resume()

        try {
            request.pipeTo(browserResponse).coAwait()

            ticket.completion.complete(0L)
        } catch (exception: Exception) {
            logger.warn("A transfer of ${ticket.fileName} broke: ${exception.message}")

            ticket.completion.completeExceptionally(exception)

            // The response head is long gone by now, so the only honest way to tell the browser
            // this file is incomplete is to drop the connection under it.
            runCatching { browserResponse.reset() }
        }

        return Successful()
    }

    companion object {
        const val ERROR_HEADER = "X-Pano-Transfer-Error"
        const val FILE_NAME_HEADER = "X-Pano-File-Name"

        /**
         * A filename that cannot break out of the `Content-Disposition` header.
         *
         * The name comes from a file on someone else's machine, so quotes, newlines and path
         * separators are all things it could legitimately contain and none of them may reach the
         * header as they are.
         */
        fun sanitise(name: String): String = name
            .replace('\\', '_')
            .replace('/', '_')
            .replace('"', '_')
            .replace('\r', '_')
            .replace('\n', '_')
            .take(200)
            .ifBlank { "download" }
    }
}
