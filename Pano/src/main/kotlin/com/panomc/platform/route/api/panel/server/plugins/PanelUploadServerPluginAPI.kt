package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginFileActionLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PathDenied
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.TransferPushMessage
import com.panomc.platform.node.transfer.BrowserUploadRelay
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferSpaceService
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.node.transfer.UploadRendezvous
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.route.api.panel.server.files.PanelUploadServerFileAPI
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginLoaderMapping
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import java.io.File

/**
 * `POST /api/v1/panel/servers/:id/plugins/upload` — a plugin or mod jar from the admin's computer,
 * dropped into the server's `plugins` (or, for a mod loader, `mods`) directory.
 *
 * Relayed to the node as it arrives, exactly like
 * [com.panomc.platform.route.api.panel.server.files.PanelUploadServerFileAPI]: no ceiling, nothing
 * on Pano's disk, the node's disk the only limit. What differs is who may do it and where it lands:
 * this is the Plugins page's upload, so it asks for the plugin permission rather than the files one,
 * and the directory is not the caller's to choose.
 *
 * Only a `.jar` that is a zip is accepted, and never one named like the Pano plugin: that jar is
 * Pano's to install and update, and a hand-uploaded copy beside it would load twice. The zip check
 * reads the first four bytes as they pass; a file that fails it is stopped there and the node drops
 * what it had.
 */
@Endpoint
class PanelUploadServerPluginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverActionRateLimiter: ServerActionRateLimiter,
    private val browserUploadRelay: BrowserUploadRelay,
    private val transferSpaceService: TransferSpaceService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/servers/:id/plugins/upload", RouteType.POST))

    // Relayed, never stored; the checks before the body still run ([checkBeforeBody]).
    override fun bodyHandler(): Handler<RoutingContext> = authorizedBodyHandler(Handler { it.next() })

    override suspend fun checkBeforeBody(context: RoutingContext) {
        val id = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.UPLOAD, userId, id)) {
            throw RateLimited()
        }
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val id = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val uploadId = context.queryParam("uploadId").firstOrNull()
        val sqlClient = getSqlClient()

        var name = ""
        var path = ""
        var directory = ""

        val header = Buffer.buffer()

        val outcome = browserUploadRelay.relay(context, uploadId, userId, inspect = { chunk ->
            if (header.length() < 4) {
                header.appendBuffer(chunk.slice(0, minOf(chunk.length(), 4 - header.length())))

                if (header.length() == 4 && !isZipHeader(header.bytes)) {
                    throw InvalidData()
                }
            }
        }) { part, declaredSize ->
            // The browser names the file: one plain jar name and nothing that could climb out of the
            // directory, and never the Pano plugin's own name.
            name = ManagedServerFileClient.normalisePath(part.filename())

            if (name.isEmpty() || name.contains('/') || !PluginFileNaming.isJarName(name)) {
                throw InvalidData()
            }

            if (PluginFileNaming.isPanoPluginJar(name)) {
                throw PathDenied()
            }

            val target = fileClient.resolve(id, sqlClient)

            directory = PluginLoaderMapping.targetDir(target.server.type)
            path = "$directory/$name"

            if (declaredSize != null) {
                TransferSpaceService.requireFits(
                    target.nodeId?.let { transferSpaceService.freeOnNode(it, target.serverUuid) },
                    declaredSize
                )
            }

            val ticket = transferTicketStore.issue(
                userId = userId,
                serverId = id,
                nodeId = target.nodeId,
                serverUuid = target.serverUuid,
                path = path,
                direction = TransferDirection.UPLOAD,
                fileName = name,
                upload = UploadRendezvous(declaredSize)
            )

            val reply = CoroutineScope(context.vertx().dispatcher()).async {
                fileClient.request(
                    target,
                    TransferPushMessage(ticket.id, target.serverUuid, path, declaredSize ?: 0L),
                    PanelUploadServerFileAPI.PUSH_REPLY_TIMEOUT_MS
                )
            }

            BrowserUploadRelay.Prepared(ticket, reply)
        }

        // An empty part never reached the zip check.
        if (header.length() < 4) {
            throw InvalidData()
        }

        val size = outcome.reply?.getLong("size") ?: outcome.bytes ?: 0L

        // A jar with this name may have come from a catalogue before; the file there now is the
        // admin's own, so whatever provenance was recorded for the old one no longer applies.
        databaseManager.serverPluginInstallDao.deleteByServerIdAndFilename(id, name, sqlClient)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        databaseManager.panelActivityLogDao.add(
            ServerPluginFileActionLog(
                userId,
                username,
                id,
                ServerPluginFileActionLog.ACTION_INSTALL,
                name,
                SOURCE_UPLOAD
            ),
            sqlClient
        )

        panelRealtimeHub.pushServerPluginsChanged(id)
        panelRealtimeHub.pushServerFilesChanged(id, directory)

        return Successful(mapOf("path" to path, "filename" to name, "size" to size))
    }

    companion object {
        /** The multipart field the panel uploads under, the same as the file manager's. */
        const val PART_NAME = "file"

        /** Where the activity log says the jar came from. */
        const val SOURCE_UPLOAD = "upload"

        /** Whether [header] (the first four bytes) opens a zip: `PK\u0003\u0004`, or an empty archive's `PK\u0005\u0006`. */
        fun isZipHeader(header: ByteArray): Boolean =
            header.size >= 4 &&
                header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                ((header[2] == 3.toByte() && header[3] == 4.toByte()) || (header[2] == 5.toByte() && header[3] == 6.toByte()))

        /** A jar is a zip: the local file header signature `PK\u0003\u0004`, or an empty archive's `PK\u0005\u0006`. */
        fun isZip(file: File): Boolean = try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)

                stream.read(header) == 4 &&
                    header[0] == 'P'.code.toByte() &&
                    header[1] == 'K'.code.toByte() &&
                    ((header[2] == 3.toByte() && header[3] == 4.toByte()) || (header[2] == 5.toByte() && header[3] == 6.toByte()))
            }
        } catch (_: Exception) {
            false
        }
    }
}
