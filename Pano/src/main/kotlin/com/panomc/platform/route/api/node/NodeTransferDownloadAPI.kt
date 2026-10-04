package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferRedeemer
import com.panomc.platform.node.transfer.TransferTicketStore
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * The node half of an upload (`GET /api/node/transfer/:ticket`).
 *
 * The browser's file is already spooled on Pano's disk by the time the node gets here; this hands
 * it over with `sendFile`, which is a zero-copy send and never loads it. The spool is deleted by
 * whoever owns the ticket, not here: the node may have to retry, and a file deleted on first read
 * would turn a retry into a silent truncation.
 */
@Endpoint
class NodeTransferDownloadAPI(
    private val transferRedeemer: TransferRedeemer,
    private val transferTicketStore: TransferTicketStore
) : Api() {
    override val paths = listOf(Path("/api/node/transfer/:ticket", RouteType.GET))

    // Authenticates with a node token, never a user JWT.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val ticketId = context.pathParam("ticket")

        // A node token or a plugin token, settled in one place: the two sides speak the same
        // transfer protocol and each may only redeem its own kind of ticket (§2.4.17 C).
        val ticket = transferRedeemer.redeem(context, ticketId, TransferDirection.UPLOAD)
            ?: return NotExists()

        val spool = ticket.spoolFile

        if (spool == null || !spool.isFile) {
            return NotExists()
        }

        context.response()
            .putHeader("Content-Type", "application/octet-stream")
            .putHeader("Content-Length", spool.length().toString())
            .sendFile(spool.absolutePath)
            .coAwait()

        return null
    }
}
