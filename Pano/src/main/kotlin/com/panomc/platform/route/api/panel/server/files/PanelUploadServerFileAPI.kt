package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.PathDenied
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.TransferPushMessage
import com.panomc.platform.node.transfer.BrowserUpload
import com.panomc.platform.node.transfer.BrowserUploadRelay
import com.panomc.platform.node.transfer.StreamRelay
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferSpaceService
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.node.transfer.UploadRendezvous
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.console.ServerActionRateLimiter
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Takes a file from the browser and hands it to the node that owns the server, as it arrives.
 *
 * Pano keeps none of it: the request has no body handler, its file part is paused at the first byte,
 * the node is told to fetch the file under a ticket, and the node's `GET` is joined to this request
 * ([BrowserUploadRelay]). So a file of any size crosses Pano as a stream, the node's disk is the only
 * limit, and the node's pace is the browser's. The upload is reported as finished only once the node
 * says it wrote the file, because "uploaded" and "arrived in the server directory" are not the same
 * thing and only the second one is useful.
 *
 * `uploadId` (optional) names the upload for `GET transfers/failures/:uploadId`, which tells the panel
 * why an upload that was cut off mid-way failed.
 */
@Endpoint
class PanelUploadServerFileAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val serverActionRateLimiter: ServerActionRateLimiter,
    private val browserUploadRelay: BrowserUploadRelay,
    private val transferSpaceService: TransferSpaceService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/servers/:id/files/upload", RouteType.POST))

    // No body handler at all: the body is relayed to the node, never stored. The wrapper still runs
    // the login, panel and permission checks before a byte is read ([checkBeforeBody]).
    override fun bodyHandler(): Handler<RoutingContext> = authorizedBodyHandler(Handler { it.next() })

    /**
     * Asked before a byte of the upload is taken: may this person write to this server's files, and is the
     * upload within the limit (§2.4.12). A refusal says how long to wait: the panel's upload queue waits that
     * long and sends the file again.
     */
    override suspend fun checkBeforeBody(context: RoutingContext) {
        val id = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val uploaderId = authProvider.getUserIdFromRoutingContext(context)

        if (!serverActionRateLimiter.tryAcquire(ServerActionRateLimiter.Action.FILE_UPLOAD, uploaderId, id)) {
            throw RateLimited(
                extras = mapOf(
                    "retryAfter" to serverActionRateLimiter.retryAfterSeconds(
                        ServerActionRateLimiter.Action.FILE_UPLOAD,
                        uploaderId,
                        id
                    )
                )
            )
        }
    }

    // Read by hand: a validation handler would want the body the route never collects.
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val id = context.pathParam("id")?.toLongOrNull() ?: throw BadRequest()

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val directory = ManagedServerFileClient.normalisePath(context.queryParam("path").firstOrNull())
        val uploadId = context.queryParam("uploadId").firstOrNull()

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = getSqlClient()

        var path = ""

        val outcome = browserUploadRelay.relay(context, uploadId, userId) { part, declaredSize ->
            // The browser names the file, so it is a path segment until proven otherwise: a name with
            // a slash in it is how an upload turns into a write somewhere else entirely.
            val name = ManagedServerFileClient.normalisePath(part.filename())

            if (name.isEmpty() || name.contains('/')) {
                throw PathDenied()
            }

            path = if (directory.isEmpty()) name else "$directory/$name"

            val target = fileClient.resolve(id, sqlClient)

            // The early answer, before anything is sent: the node checks again while writing.
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
                    PUSH_REPLY_TIMEOUT_MS
                )
            }

            BrowserUploadRelay.Prepared(ticket, reply)
        }

        val size = outcome.reply?.getLong("size") ?: outcome.bytes ?: 0L

        fileClient.log(context, id, ServerFileChangedLog.ACTION_UPLOAD, path, sqlClient = sqlClient)

        panelRealtimeHub.pushServerFilesChanged(id, directory)

        return Successful(mapOf("path" to path, "size" to size))
    }

    companion object {
        /** The multipart field the panel uploads under. */
        const val PART_NAME = BrowserUpload.PART_NAME

        /**
         * How long the node's answer to a push is waited for. Not a limit on the upload: a transfer that
         * stops moving ends after five minutes ([StreamRelay.IDLE_TIMEOUT_MS]) and the node then answers
         * with the failure. This only bounds a node that never answers at all.
         */
        const val PUSH_REPLY_TIMEOUT_MS = 48 * 60 * 60 * 1000L
    }
}
