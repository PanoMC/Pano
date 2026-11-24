package com.panomc.platform.route.api.panel.transfers

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.TransferExpired
import com.panomc.platform.model.*
import com.panomc.platform.node.transfer.BrowserUploadRelay
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `POST /api/v1/panel/transfers/upload?ticket=<id>` — the archive of a server being created.
 *
 * The last step of the create wizard, by design: `servers/create` makes the row, issues `ticket` and
 * starts the import, whose node is already waiting at `GET /api/v1/node/transfer/<ticket>`. This
 * request is joined to that one and the archive streams through Pano onto the node, which unpacks it
 * as it arrives (7z and rar: it saves it first). Nothing is kept here, so there is no size ceiling;
 * the node's disk is the limit, and it stops the import when it would run out.
 *
 * Answered once every byte reached the node. Whether the import then succeeded is the import task's
 * to say, and a failed upload (cancelled, cut off, no space) fails that task, which removes the server
 * again: an import that never got its files is not a server.
 */
@Endpoint
class PanelUploadTransferAPI(
    private val authProvider: AuthProvider,
    private val transferTicketStore: TransferTicketStore,
    private val browserUploadRelay: BrowserUploadRelay
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/transfers/upload", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    // Relayed, never stored. Login, panel access and the permission are checked before the body.
    override fun bodyHandler(): Handler<RoutingContext> = authorizedBodyHandler(Handler { it.next() })

    override suspend fun checkBeforeBody(context: RoutingContext) {
        authProvider.requirePermission(CreateServersPermission(), context)
    }

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val ticketId = context.queryParam("ticket").firstOrNull()?.takeIf { it.isNotBlank() } ?: throw BadRequest()
        val uploadId = context.queryParam("uploadId").firstOrNull()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val outcome = browserUploadRelay.relay(context, uploadId, userId) { _, _ ->
            // Only the person who pressed Create, only once, and only while the import still waits.
            val ticket = transferTicketStore.resolveUpload(ticketId, userId) ?: throw TransferExpired()

            BrowserUploadRelay.Prepared(ticket)
        }

        return Successful(mapOf("size" to (outcome.bytes ?: 0L)))
    }
}
