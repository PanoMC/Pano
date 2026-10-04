package com.panomc.platform.route.api.panel.server.plugins

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerPluginFileActionLog
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PathDenied
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.TransferPushMessage
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.server.plugins.PluginFileNaming
import com.panomc.platform.server.plugins.PluginLoaderMapping
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.kotlin.coroutines.coAwait
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `POST /api/panel/servers/:id/plugins/upload` — a plugin or mod jar from the admin's computer,
 * dropped into the server's `plugins` (or, for a mod loader, `mods`) directory.
 *
 * The same three hops as [com.panomc.platform.route.api.panel.server.files.PanelUploadServerFileAPI]
 * — Vert.x spools the multipart body to disk, the spool moves under a transfer ticket, the node
 * fetches it with an ordinary GET — so a large jar never sits in Pano's heap. What differs is who
 * may do it and where it lands: this is the Plugins page's upload, so it asks for the plugin
 * permission rather than the files one, and the directory is not the caller's to choose.
 *
 * Only a `.jar` that is a zip is accepted, and never one named like the Pano plugin: that jar is
 * Pano's to install and update, and a hand-uploaded copy beside it would load twice.
 */
@Endpoint
class PanelUploadServerPluginAPI(
    private val vertx: Vertx,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/plugins/upload", RouteType.POST))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(MAX_UPLOAD_BYTES)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerPluginsPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.UPLOAD, userId, id)) {
            throw RateLimited()
        }

        val uploads = context.fileUploads()

        val upload = uploads.firstOrNull { it.name() == PART_NAME }
            ?: uploads.singleOrNull()
            ?: throw BadRequest()

        if (upload.size() > MAX_UPLOAD_BYTES) {
            throw FileTooLarge()
        }

        // The browser names the file: one plain jar name and nothing that could climb out of the
        // directory, and never the Pano plugin's own name.
        val name = ManagedServerFileClient.normalisePath(upload.fileName())

        if (name.isEmpty() || name.contains('/') || !PluginFileNaming.isJarName(name)) {
            throw InvalidData()
        }

        if (PluginFileNaming.isPanoPluginJar(name)) {
            throw PathDenied()
        }

        val uploaded = File(upload.uploadedFileName())

        if (!isZip(uploaded)) {
            throw InvalidData()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val directory = PluginLoaderMapping.targetDir(target.server.type)
        val path = "$directory/$name"

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = id,
            nodeId = target.nodeId,
            serverUuid = target.serverUuid,
            path = path,
            direction = TransferDirection.UPLOAD,
            fileName = name
        )

        val spool = transferTicketStore.spoolFileFor(ticket.id)

        ticket.spoolFile = spool

        val size: Long

        try {
            moveSpool(uploaded, spool)

            size = spool.length()

            fileClient.request(
                target,
                TransferPushMessage(ticket.id, target.serverUuid, path, size),
                NodeManager.TRANSFER_REQUEST_TIMEOUT_MS
            )
        } finally {
            transferTicketStore.discard(ticket)
        }

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

    /** Moves the multipart spool under the ticket, falling back to a copy across filesystems. */
    private suspend fun moveSpool(uploaded: File, spool: File) {
        vertx.executeBlocking<Unit> {
            spool.parentFile?.mkdirs()

            try {
                Files.move(uploaded.toPath(), spool.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                uploaded.copyTo(spool, overwrite = true)
            }
        }.coAwait()
    }

    companion object {
        /** The multipart field the panel uploads under, the same as the file manager's. */
        const val PART_NAME = "file"

        /** Where the activity log says the jar came from. */
        const val SOURCE_UPLOAD = "upload"

        /** Far above any real plugin or mod; a modded server's biggest jars are tens of megabytes. */
        const val MAX_UPLOAD_BYTES = 256L * 1024 * 1024

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
