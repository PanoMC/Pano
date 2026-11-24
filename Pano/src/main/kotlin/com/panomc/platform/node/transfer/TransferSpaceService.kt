package com.panomc.platform.node.transfer

import com.panomc.platform.error.NotEnoughSpace
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.NodeProtocol
import com.panomc.platform.node.message.DiskSpaceMessage
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File

/**
 * The one rule every upload follows now that there is no size ceiling: it has to fit on the disk.
 *
 * "Fit" keeps [RESERVE_BYTES] free on top of the file, because a disk filled to the last byte stops
 * the node's logs, its database and every other server on it from writing. The node applies the same
 * reserve while the bytes arrive; the answer here is only the early one, given before anybody waits.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class TransferSpaceService(
    private val nodeManager: NodeManager,
    private val logger: Logger
) {
    /**
     * Free bytes on the disk of [nodeId] that holds [serverUuid]'s directory (its data directory when
     * null), or null when the node cannot say: too old for `DISK_SPACE`, or it did not answer.
     */
    suspend fun freeOnNode(nodeId: Long, serverUuid: String? = null): Long? {
        val node = nodeManager.getConnectedNodeById(nodeId) ?: return null

        if (!NodeProtocol.supportsDiskSpace(node.protocolVersion)) {
            return null
        }

        return try {
            val payload = nodeManager.request(nodeId, DiskSpaceMessage(serverUuid), DISK_SPACE_TIMEOUT_MS)

            payload.getLong("usable")?.takeIf { payload.getBoolean("ok", false) }
        } catch (exception: Exception) {
            logger.warn("Node $nodeId did not report its free disk space: ${exception.message}")

            null
        }
    }

    companion object {
        /** Space that is never handed to an upload (owner decision, 2026-11-25). */
        const val RESERVE_BYTES = 256L * 1024 * 1024

        private const val DISK_SPACE_TIMEOUT_MS = 10_000L

        /** Free bytes on the disk that holds [directory] on Pano's own machine. */
        fun freeOnPano(directory: File): Long {
            var existing: File? = directory.absoluteFile

            while (existing != null && !existing.exists()) {
                existing = existing.parentFile
            }

            return existing?.usableSpace ?: 0L
        }

        /** What a file of [size] bytes needs free: itself and the reserve. */
        fun required(size: Long): Long = size.coerceAtLeast(0) + RESERVE_BYTES

        /** Throws [NotEnoughSpace] when [free] is known and a file of [size] bytes does not fit in it. */
        fun requireFits(free: Long?, size: Long) {
            if (free == null) {
                return
            }

            val required = required(size)

            if (free < required) {
                throw NotEnoughSpace(extras = mapOf("required" to required, "free" to free))
            }
        }
    }
}
