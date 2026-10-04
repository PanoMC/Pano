package com.panomc.platform.node

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.FileOperationFailed
import com.panomc.platform.error.PathDenied
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.ServerCapabilityMissing
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.channel.NodeSideChannel
import com.panomc.platform.server.channel.PluginSideChannel
import com.panomc.platform.server.channel.ServerSideChannel
import com.panomc.platform.server.feature.ServerFeature
import com.panomc.platform.server.feature.ServerFeatureResolver
import com.panomc.platform.server.feature.ServerFeatureSource
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The one way a panel endpoint talks to a node about files.
 *
 * Three things have to happen for every single file operation and none of them are interesting
 * enough to repeat eleven times: find the server and prove a node owns it, normalise and sanity
 * check the path before it goes anywhere near a filesystem, and turn the node's `{ok, error}`
 * answer into the error the panel understands. Putting them here means a new file endpoint cannot
 * forget one — the checks are not next to the code, they are the way to reach the code.
 *
 * Pano's path check is deliberately not the security boundary. The node checks everything again,
 * because it is the side that owns the disk and the only side that can see a symlink. This one
 * exists so an obvious mistake fails immediately with a clear message instead of travelling over a
 * socket first.
 *
 * Since SM-52 the other end is not always a node. The Pano plugin answers the same `FILE_*`,
 * `BACKUP_*` and `INSTALL_PLUGIN` shapes from inside the running server (§2.4.17 C), so `resolve`
 * asks [ServerFeatureResolver] which side should serve the feature and hands back a
 * [ServerSideChannel] rather than a node id. Every endpoint below this line is unchanged by that,
 * which is the point of the abstraction.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedServerFileClient(
    private val databaseManager: DatabaseManager,
    private val nodeManager: NodeManager,
    private val serverManager: ServerManager,
    private val serverFeatureResolver: ServerFeatureResolver,
    private val authProvider: AuthProvider
) {
    /** A server together with whichever side of it can currently do the work. */
    data class Target(val server: Server, val channel: ServerSideChannel) {
        val serverUuid get() = channel.serverUuid

        /** The node behind this target, or null when the plugin is answering. */
        val nodeId get() = channel.nodeId

        val source get() = channel.source
    }

    /**
     * Resolves [serverId] into something the work can be sent to.
     *
     * [feature] is what decides the side, so a caller asking about backups is not refused because
     * the node that would have served its files is offline. A server nothing can reach for that
     * feature fails with [FeatureUnavailable] naming it, which is the answer the panel disables
     * the control with.
     */
    suspend fun resolve(
        serverId: Long,
        sqlClient: SqlClient,
        feature: ServerFeature = ServerFeature.FILES_SOURCE
    ): Target {
        val server = databaseManager.serverDao.getById(serverId, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        return when (serverFeatureResolver.pick(server, feature)) {
            ServerFeatureSource.NODE -> {
                val nodeId = server.nodeId ?: throw ServerCapabilityMissing()
                // The node addresses a server by the uuid it was installed under.
                val uuid = server.uuid ?: throw ServerCapabilityMissing()

                if (!nodeManager.isConnected(nodeId)) {
                    throw NodeOffline()
                }

                Target(server, NodeSideChannel(nodeId, uuid, nodeManager))
            }

            // The plugin's socket already names the server, so a linked row -- which never had a
            // node-side uuid -- is addressed by its id alone.
            else -> Target(server, PluginSideChannel(server.id, server.uuid ?: "", serverManager))
        }
    }

    /**
     * Sends [message] to [target]'s node and hands back the payload, or throws.
     *
     * The node's error strings are mapped rather than passed through: `PATH_DENIED` is a different
     * thing for a panel to show than "no space left on device", and the panel should not have to
     * know the daemon's vocabulary to tell them apart.
     */
    suspend fun request(
        target: Target,
        message: NodeRequestMessage,
        timeoutMs: Long = NodeManager.DEFAULT_REQUEST_TIMEOUT_MS
    ): JsonObject {
        val payload = target.channel.request(message, timeoutMs)

        if (payload.getBoolean("ok", false)) {
            return payload
        }

        throw toError(payload.getString("error"))
    }

    /**
     * Pushes [message] without waiting for anything.
     *
     * For the one case where the answer does not come back on the socket: a download is finished
     * by the node's own HTTP request arriving at Pano, so waiting for a reply here would mean two
     * things to time out where there is only one outcome.
     */
    fun send(target: Target, message: NodeMessage) {
        target.channel.send(message)
    }

    /**
     * Writes the audit entry for one mutation.
     *
     * Here rather than in each endpoint so no file operation can ship without one: every endpoint
     * that changes something already has to come through this class, and this is the line it has
     * to write on the way out.
     */
    suspend fun log(
        context: RoutingContext,
        serverId: Long,
        action: String,
        path: String,
        target: String? = null,
        sqlClient: SqlClient
    ) {
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerFileChangedLog(userId, username, serverId, action, path, target),
            sqlClient
        )
    }

    private fun toError(error: String?): Throwable = when (error) {
        ERROR_PATH_DENIED, ERROR_IN_USE -> PathDenied()
        ERROR_TOO_LARGE -> FileTooLarge()
        ERROR_NOT_FOUND, ERROR_UNKNOWN_SERVER, ERROR_NO_SERVER_DIRECTORY -> NotExists()
        else -> FileOperationFailed(extras = mapOf("message" to (error ?: "The node could not do that.")))
    }

    companion object {
        const val ERROR_PATH_DENIED = "PATH_DENIED"
        const val ERROR_NOT_FOUND = "NOT_FOUND"
        const val ERROR_TOO_LARGE = "TOO_LARGE"
        const val ERROR_UNKNOWN_SERVER = "UNKNOWN_SERVER"

        /** The plugin's refusal to write over a jar the JVM already has open (§2.4.17 C). */
        const val ERROR_IN_USE = "IN_USE"

        /** The plugin could not work out where its server's directory is. */
        const val ERROR_NO_SERVER_DIRECTORY = "NO_SERVER_DIRECTORY"

        /** Longest path the panel may ask about, so a pathological one never reaches a filesystem. */
        const val MAX_PATH_LENGTH = 1024

        /** Most paths one delete or archive may name. */
        const val MAX_PATHS = 500

        /**
         * Normalises a path from the panel and refuses what can only be an attack.
         *
         * Mirrors the node's own rules so the two cannot drift: separators become `/`, empty and
         * `.` segments disappear, and `..`, a NUL, an absolute path or a Windows drive letter is
         * refused outright. The result is always relative to the server directory; an empty string
         * is the server directory itself, which is what a listing of the root asks for.
         */
        fun normalisePath(path: String?): String {
            val raw = path.orEmpty()

            if (raw.length > MAX_PATH_LENGTH) {
                throw PathDenied()
            }

            if (raw.contains('\u0000')) {
                throw PathDenied()
            }

            val unified = raw.replace('\\', '/')

            if (unified.startsWith("/") || DRIVE_LETTER.containsMatchIn(unified)) {
                throw PathDenied()
            }

            val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }

            if (segments.any { it == ".." }) {
                throw PathDenied()
            }

            return segments.joinToString("/")
        }

        /** [normalisePath] for a list, with the batch ceiling applied. */
        fun normalisePaths(paths: List<String>?): List<String> {
            val requested = paths.orEmpty()

            if (requested.isEmpty() || requested.size > MAX_PATHS) {
                throw PathDenied()
            }

            return requested.map { normalisePath(it) }.filter { it.isNotEmpty() }
        }

        /** The last segment of a path, for a download's filename. */
        fun fileNameOf(path: String): String = path.substringAfterLast('/').ifEmpty { "download" }

        private val DRIVE_LETTER = Regex("^[A-Za-z]:")
    }
}
