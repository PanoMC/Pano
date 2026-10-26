package com.panomc.platform.backup

import com.panomc.platform.backup.remote.PackSlot
import com.panomc.platform.backup.remote.UploadSession
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * What a node sending a backup straight to Pano Backup (protocol 8) asks of the upload job behind its ticket: what only
 * the control plane answers, and how far it got.
 */
interface DirectUploadHandler {
    /** The node's progress: `ARCHIVING` / `UPLOADING`, bytes done of [total] (0 = unknown). */
    fun progress(phase: String, done: Long, total: Long)

    /** An archive of [size] bytes ([sha256]) is ready: the upload session it is PUT into. */
    suspend fun archive(size: Long, sha256: String): UploadSession

    /** The ids of [chunks] the repository lacks (snapshot). */
    suspend fun missing(snapshotId: String, chunks: List<Pair<String, Int>>): Set<String>

    /** A pack slot of exactly [size] bytes (snapshot). */
    suspend fun packSlot(snapshotId: String, size: Long): PackSlot
}

/**
 * The tickets of direct uploads (`/api/v1/node/pano-backup/:ticket/:action`): an unguessable id bound to the node that
 * sends the backup and to the job that sent it there. Lives as long as that one request to the node.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoBackupUploadTickets {
    class Ticket(val id: String, val nodeId: Long, val handler: DirectUploadHandler)

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val random = SecureRandom()

    fun issue(nodeId: Long, handler: DirectUploadHandler): Ticket {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val ticket = Ticket(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), nodeId, handler)

        tickets[ticket.id] = ticket

        return ticket
    }

    /** The ticket [id] when it belongs to [nodeId], null otherwise (another node's ticket is no ticket). */
    fun resolve(id: String?, nodeId: Long): Ticket? = id?.let { tickets[it] }?.takeIf { it.nodeId == nodeId }

    fun discard(ticket: Ticket) {
        tickets.remove(ticket.id, ticket)
    }
}
