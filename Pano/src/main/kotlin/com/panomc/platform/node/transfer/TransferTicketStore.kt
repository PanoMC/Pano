package com.panomc.platform.node.transfer

import com.panomc.platform.config.ConfigManager
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerResponse
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Which way a transfer moves, named from Pano's point of view. */
enum class TransferDirection {
    /** Out of the server, towards a browser. */
    DOWNLOAD,

    /** Into the server, from a browser. */
    UPLOAD,

    /**
     * Into Pano's spool, from a browser, for something that has no server yet.
     *
     * An import uploads its archive before the server row exists, so the ticket cannot be bound to
     * a server or a node at the moment it is issued. It is bound later, by the endpoint that
     * creates the server, and until then it belongs to nobody but the person who uploaded it —
     * which is why it is a direction of its own rather than an [UPLOAD] with blank fields that
     * every node would be allowed to redeem.
     */
    UPLOAD_SPOOL
}

/**
 * One authorised file transfer, and everything needed to finish it.
 *
 * The ticket is the whole authorisation: `PUT /api/node/transfer/<id>` carries a node's or a
 * plugin's token and nothing else about who asked or what for, so the ticket is what says who may
 * use it, for which server, on which path, in which direction — and it stops being usable ten
 * minutes later whether or not anybody used it.
 *
 * [nodeId] is null for a ticket an agent-lite plugin is to redeem (§2.4.17 C). Such a ticket is
 * bound to [serverId] instead, and the two are checked by different `resolve` calls so a node can
 * never redeem a plugin's ticket by knowing its id, or the other way round.
 */
data class TransferTicket(
    val id: String,
    val userId: Long,
    var serverId: Long,
    var nodeId: Long?,
    var serverUuid: String,
    val path: String,
    var direction: TransferDirection,
    val fileName: String,
    val expiresAt: Long,
    /**
     * The browser response a download is being pumped into.
     *
     * Held here because the two halves of a download arrive as two separate HTTP requests: the
     * person's `GET …/files/download` parks on this ticket, and the node's `PUT` is what actually
     * writes the bytes into it.
     */
    val browserResponse: HttpServerResponse? = null,
    /** Completed when the download has been written, or failed with the reason it was not. */
    val completion: CompletableDeferred<Long> = CompletableDeferred(),
    /** Where an upload was spooled while it waits for the node to fetch it. */
    var spoolFile: File? = null,
    /**
     * The `Content-Type` a download is served with; null is `application/octet-stream`.
     *
     * Only ever set by Pano itself — a zip it asked for, or a preview type from its own allowlist —
     * and never taken from the source, which is someone else's machine deciding how the panel's
     * origin renders a file.
     */
    val contentType: String? = null,
    /**
     * Whether a download is shown in the browser rather than saved.
     *
     * Used by the file manager's preview, and only for the image, video and audio types that
     * cannot run anything on the panel's origin.
     */
    val inline: Boolean = false
) {
    val isExpired get() = System.currentTimeMillis() > expiresAt
}

/**
 * The transfer tickets that are currently live, and the rules for redeeming one.
 *
 * In memory rather than in a table on purpose: a ticket is worthless ten minutes after it is
 * issued and worthless immediately after a restart — the browser it belonged to is gone either
 * way — so persisting it would only create rows nobody ever reads and a window in which a stale
 * one could still be redeemed.
 *
 * Deliberately free of Vert.x and of Pano's configuration, so the part that decides who may redeem
 * what can be tested directly. The bean around it owns the sweep timer and the spool directory.
 */
class TransferTicketRegistry(
    private val onDiscard: (TransferTicket) -> Unit = {}
) {
    private val tickets = ConcurrentHashMap<String, TransferTicket>()

    /** Issues a ticket and remembers it until it is used or expires. */
    fun issue(
        userId: Long,
        serverId: Long,
        nodeId: Long?,
        serverUuid: String,
        path: String,
        direction: TransferDirection,
        fileName: String,
        browserResponse: HttpServerResponse? = null,
        ttlMs: Long = TTL_MS,
        contentType: String? = null,
        inline: Boolean = false
    ): TransferTicket {
        val ticket = TransferTicket(
            id = UUID.randomUUID().toString(),
            userId = userId,
            serverId = serverId,
            nodeId = nodeId,
            serverUuid = serverUuid,
            path = path,
            direction = direction,
            fileName = fileName,
            expiresAt = System.currentTimeMillis() + ttlMs,
            browserResponse = browserResponse,
            contentType = contentType,
            inline = inline
        )

        tickets[ticket.id] = ticket

        return ticket
    }

    /**
     * The ticket [id] names, if it is still live and belongs to [nodeId] in [direction].
     *
     * Every condition is checked in one place because they are one condition: a ticket that is
     * expired, for another node, or for the other direction is not a ticket that happens to be
     * unusable, it is a request that has no business being answered.
     */
    fun resolve(id: String?, nodeId: Long, direction: TransferDirection): TransferTicket? =
        redeemable(id, direction) { it.nodeId == nodeId }

    /**
     * The ticket [id] names, if it is live and belongs to the server whose plugin is asking.
     *
     * The plugin counterpart of [resolve], and deliberately a second method rather than a second
     * argument: a ticket issued for a node must not become redeemable by a server that guessed
     * its id, and the only way to be sure of that is for the two checks never to share a branch.
     */
    fun resolveForServer(id: String?, serverId: Long, direction: TransferDirection): TransferTicket? =
        redeemable(id, direction) { it.nodeId == null && it.serverId == serverId }

    private fun redeemable(
        id: String?,
        direction: TransferDirection,
        owner: (TransferTicket) -> Boolean
    ): TransferTicket? {
        val ticket = tickets[id ?: return null] ?: return null

        if (ticket.isExpired) {
            discard(ticket)

            return null
        }

        if (!owner(ticket) || ticket.direction != direction) {
            return null
        }

        return ticket
    }

    /**
     * The spooled upload [id] names, if it is still live and belongs to [userId].
     *
     * The counterpart of [resolve] for a ticket no node owns yet: a spool is authorised by the
     * person who uploaded it, and by nothing else, until it is bound to a server.
     */
    fun resolveSpool(id: String?, userId: Long): TransferTicket? {
        val ticket = tickets[id ?: return null] ?: return null

        if (ticket.isExpired) {
            discard(ticket)

            return null
        }

        if (ticket.direction != TransferDirection.UPLOAD_SPOOL || ticket.userId != userId) {
            return null
        }

        return ticket
    }

    /**
     * Hands a spooled upload to the node that is about to fetch it.
     *
     * The moment an import picks a node is the moment the ticket stops being anonymous, so this is
     * also where it becomes an ordinary [TransferDirection.UPLOAD] that only that node may redeem.
     */
    fun bind(ticket: TransferTicket, serverId: Long, nodeId: Long?, serverUuid: String) {
        ticket.serverId = serverId
        ticket.nodeId = nodeId
        ticket.serverUuid = serverUuid
        ticket.direction = TransferDirection.UPLOAD
    }

    /** Takes a ticket out of circulation, so the same one cannot be redeemed twice. */
    fun consume(id: String): TransferTicket? = tickets.remove(id)

    /** Removes a ticket and hands whatever it had spooled to the owner to clean up. */
    fun discard(ticket: TransferTicket) {
        tickets.remove(ticket.id, ticket)

        onDiscard(ticket)
    }

    /** How many tickets are live. */
    fun size() = tickets.size

    /** Drops every expired ticket and tells whoever was waiting on it. */
    fun sweep() {
        tickets.values
            .filter { it.isExpired }
            .forEach { ticket ->
                discard(ticket)

                // Whoever was waiting on this download is told rather than left holding an open
                // response until their browser gives up on it.
                ticket.completion.completeExceptionally(IllegalStateException("The transfer expired."))
            }
    }

    companion object {
        /** Long enough for a slow 1 GB transfer, short enough that a leaked ticket is worthless. */
        const val TTL_MS = 10 * 60 * 1000L

        /**
         * How long an uploaded import archive waits for its wizard.
         *
         * Longer than an ordinary transfer because the person is still working: they upload the
         * zip on one step of the create wizard and press Create on another, with a node and a name
         * to choose in between.
         */
        const val SPOOL_TTL_MS = 30 * 60 * 1000L

        const val SWEEP_INTERVAL_MS = 60 * 1000L

        /** Under `file-uploads-folder`, so it inherits whatever an operator chose for that. */
        const val SPOOL_DIRECTORY = "transfer"
    }
}

/**
 * The bean every endpoint uses: a [TransferTicketRegistry] plus the two things it cannot own.
 *
 * The sweep timer needs Vert.x and the spool directory needs Pano's configuration, and neither
 * belongs in the part that decides whether a ticket may be redeemed.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class TransferTicketStore(
    private val vertx: Vertx,
    private val configManager: ConfigManager,
    private val logger: Logger
) {
    private val registry = TransferTicketRegistry { ticket -> deleteSpool(ticket) }

    private var sweepTimer: Long = -1

    /** Arms the expiry sweep. Safe to call more than once. */
    fun init() {
        if (sweepTimer >= 0) {
            return
        }

        sweepTimer = vertx.setPeriodic(TransferTicketRegistry.SWEEP_INTERVAL_MS) { registry.sweep() }
    }

    fun issue(
        userId: Long,
        serverId: Long,
        nodeId: Long?,
        serverUuid: String,
        path: String,
        direction: TransferDirection,
        fileName: String,
        browserResponse: HttpServerResponse? = null,
        ttlMs: Long = TransferTicketRegistry.TTL_MS,
        contentType: String? = null,
        inline: Boolean = false
    ): TransferTicket {
        init()

        return registry.issue(
            userId,
            serverId,
            nodeId,
            serverUuid,
            path,
            direction,
            fileName,
            browserResponse,
            ttlMs,
            contentType,
            inline
        )
    }

    fun resolve(id: String?, nodeId: Long, direction: TransferDirection) = registry.resolve(id, nodeId, direction)

    /** [resolve] for a ticket an agent-lite plugin redeems on its own server's behalf. */
    fun resolveForServer(id: String?, serverId: Long, direction: TransferDirection) =
        registry.resolveForServer(id, serverId, direction)

    fun resolveSpool(id: String?, userId: Long) = registry.resolveSpool(id, userId)

    fun bind(ticket: TransferTicket, serverId: Long, nodeId: Long?, serverUuid: String) =
        registry.bind(ticket, serverId, nodeId, serverUuid)

    fun consume(id: String) = registry.consume(id)

    fun discard(ticket: TransferTicket) = registry.discard(ticket)

    fun size() = registry.size()

    /** Where an upload is spooled while the node comes to fetch it. */
    fun spoolFileFor(ticketId: String): File = File(spoolDirectory(), ticketId)

    /** The directory holding every spooled upload, created on demand. */
    fun spoolDirectory(): File {
        val directory = File(configManager.config.fileUploadsFolder, TransferTicketRegistry.SPOOL_DIRECTORY)

        directory.mkdirs()

        return directory
    }

    private fun deleteSpool(ticket: TransferTicket) {
        ticket.spoolFile?.let { file ->
            if (file.isFile && !file.delete()) {
                logger.warn("Could not delete the transfer spool ${file.absolutePath}.")
            }
        }
    }

    companion object {
        /** Re-exported so callers do not have to know the registry holds it. */
        const val TTL_MS = TransferTicketRegistry.TTL_MS

        /** Re-exported, for the wizard's uploaded import archive. */
        const val SPOOL_TTL_MS = TransferTicketRegistry.SPOOL_TTL_MS
    }
}
