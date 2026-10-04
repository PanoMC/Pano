package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.FileTooLarge
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.message.TransferPushMessage
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerIconImage
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.util.ImageValidationUtil
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
import java.util.Base64
import com.panomc.platform.util.UsageMode

/**
 * Sets a server's icon from a picture uploaded in the panel header.
 *
 * The picture becomes the 64×64 `server-icon.png` Minecraft wants ([ServerIconImage]), is written to
 * the server's root through the same file source the Files page uses — the node for a managed
 * server, the plugin's `files` for a linked one, `FEATURE_UNAVAILABLE` when neither can — and is
 * stored as the server's favicon so the panel shows it at once. The game reads the file only when it
 * starts, which is what `restartRequired` tells a running server's owner.
 */
@Endpoint
class PanelUploadServerIconAPI(
    private val vertx: Vertx,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverActionRateLimiter: ServerActionRateLimiter
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/icon", RouteType.POST))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            // The image's own limit plus room for the multipart framing around it.
            .setBodyLimit(ServerIconImage.MAX_BYTES + MULTIPART_OVERHEAD_BYTES)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServersPermission(), context, id)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        // An icon is an upload like any other, so it shares the uploads' much stricter limit.
        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.UPLOAD, userId, id)) {
            throw RateLimited()
        }

        val uploads = context.fileUploads()

        val upload = uploads.firstOrNull { it.name() == PART_NAME }
            ?: uploads.singleOrNull()
            ?: throw BadRequest()

        if (upload.size() > ServerIconImage.MAX_BYTES) {
            throw FileTooLarge()
        }

        val bytes = vertx.fileSystem().readFile(upload.uploadedFileName()).coAwait().bytes

        // Decoding and scaling are CPU work, kept off the event loop.
        val png = try {
            vertx.executeBlocking<ByteArray> { ServerIconImage.toServerIcon(bytes) }.coAwait()
        } catch (invalid: ServerIconImage.InvalidImage) {
            throw BadRequest(extras = mapOf("message" to (invalid.message ?: "The file is not an image.")))
        }

        val sqlClient = getSqlClient()

        // FILES_SOURCE decides the side, and a server nothing can write files for fails here with
        // FEATURE_UNAVAILABLE — before anything was stored.
        val target = fileClient.resolve(id, sqlClient)

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = id,
            nodeId = target.nodeId,
            serverUuid = target.serverUuid,
            path = ICON_PATH,
            direction = TransferDirection.UPLOAD,
            fileName = ICON_PATH
        )

        val spool = transferTicketStore.spoolFileFor(ticket.id)

        ticket.spoolFile = spool

        try {
            writeSpool(spool, png)

            fileClient.request(
                target,
                TransferPushMessage(ticket.id, target.serverUuid, ICON_PATH, png.size.toLong()),
                NodeManager.TRANSFER_REQUEST_TIMEOUT_MS
            )
        } finally {
            transferTicketStore.discard(ticket)
        }

        // The same data URL shape a plugin reports its own icon in, checked by the same rule.
        val favicon = ImageValidationUtil.sanitizeFaviconDataUrl(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        ) ?: throw BadRequest()

        databaseManager.serverDao.updateFaviconById(id, favicon, sqlClient)

        fileClient.log(context, id, ServerFileChangedLog.ACTION_UPLOAD, ICON_PATH, sqlClient = sqlClient)

        panelRealtimeHub.pushServerFilesChanged(id, "")
        panelRealtimeHub.notifyServerUpdated(id)

        val server = target.server

        // Minecraft reads server-icon.png once, at start: a server that is up keeps the old icon in
        // the server list until it is restarted.
        val running = server.processState?.isAlive == true || server.status == ServerStatus.ONLINE

        return Successful(mapOf("favicon" to favicon, "restartRequired" to running))
    }

    private suspend fun writeSpool(spool: File, png: ByteArray) {
        vertx.executeBlocking<Unit> {
            spool.parentFile?.mkdirs()
            spool.writeBytes(png)
        }.coAwait()
    }

    companion object {
        /** The multipart field the panel's header sends the picture under. */
        const val PART_NAME = "icon"

        /** Where Minecraft looks for the icon: the server's root. */
        const val ICON_PATH = "server-icon.png"

        private const val MULTIPART_OVERHEAD_BYTES = 64L * 1024L
    }
}
