package com.panomc.platform.route.api.panel.transfers

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/panel/transfers/failures/:uploadId` — why one of the caller's uploads failed.
 *
 * An upload that fails while the browser is still sending is answered and then cut off, and most
 * browsers report that as a network error without the answer. The panel sends an id with every
 * upload and asks here afterwards, so "the node's disk is full" reaches the person instead of
 * "the connection was lost". Only the person who uploaded can read it, for ten minutes.
 */
@Endpoint
class PanelGetTransferFailureAPI(
    private val authProvider: AuthProvider,
    private val transferTicketStore: TransferTicketStore
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/transfers/failures/:uploadId", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val failure = transferTicketStore.failure(context.pathParam("uploadId"), userId) ?: throw NotExists()

        return Successful(mapOf("error" to failure.code) + failure.extras)
    }
}
