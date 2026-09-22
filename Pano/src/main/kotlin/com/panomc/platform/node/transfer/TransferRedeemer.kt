package com.panomc.platform.node.transfer

import com.panomc.platform.node.NodeAuthProvider
import com.panomc.platform.server.ServerAuthProvider
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Works out who is redeeming a transfer ticket, and hands back the ticket only if it is theirs.
 *
 * Two kinds of caller now arrive at `/api/node/transfer/:ticket` with two kinds of token: the node
 * daemon that owns a managed server's files, and the Pano plugin inside a server that has no node
 * (§2.4.17 C). They use the same endpoints on purpose — the plugin implements the node's transfer
 * protocol verbatim, down to the headers — so the difference has to be settled once, here, rather
 * than in each of the two endpoints.
 *
 * The node token is tried first because it is the overwhelmingly common case, and each kind may
 * only redeem tickets of its own kind: a ticket issued for a node is invisible to
 * [TransferTicketStore.resolveForServer] and vice versa, so guessing an id gets a caller nothing.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class TransferRedeemer(
    private val nodeAuthProvider: NodeAuthProvider,
    private val serverAuthProvider: ServerAuthProvider,
    private val transferTicketStore: TransferTicketStore
) {
    /**
     * The live ticket [ticketId] names for whoever holds the token on [context], or null.
     *
     * Null covers every way this can fail — no token, a token for something else, an expired
     * ticket, a ticket for the other direction, a ticket belonging to somebody else — because to
     * the caller they are one answer: this request has no business being answered.
     */
    suspend fun redeem(
        context: RoutingContext,
        ticketId: String?,
        direction: TransferDirection
    ): TransferTicket? {
        if (nodeAuthProvider.isAuthenticated(context)) {
            val nodeId = nodeAuthProvider.getNodeIdFromRoutingContext(context) ?: return null

            return transferTicketStore.resolve(ticketId, nodeId, direction)
        }

        if (serverAuthProvider.isAuthenticated(context)) {
            return transferTicketStore.resolveForServer(
                ticketId,
                serverAuthProvider.getServerIdFromRoutingContext(context),
                direction
            )
        }

        return null
    }
}
