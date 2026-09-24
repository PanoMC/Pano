package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.FileOperationFailed
import com.panomc.platform.error.TransferExpired
import com.panomc.platform.error.PathDenied
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.TransferPullMessage
import com.panomc.platform.node.transfer.TransferDirection
import com.panomc.platform.node.transfer.TransferTicketStore
import com.panomc.platform.server.files.InlinePreviewTypes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.explodedParam
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlinx.coroutines.withTimeoutOrNull
import com.panomc.platform.util.UsageMode

/**
 * Streams files out of a managed server to the browser: one file as it is, several files or whole
 * directories as one zip, or a single image, video or audio file shown inline as a preview.
 *
 * The bytes never touch Pano's disk and never sit in its heap: this request parks on a ticket, the
 * node opens `PUT /api/node/transfer/<ticket>` and that request's body is piped straight into this
 * response. What the person downloads is therefore limited by their connection and the node's, not
 * by how much memory Pano has. A zip is no exception — the source builds it while it sends it, so
 * there is no archive anywhere, only a stream that happens to be one.
 *
 * `path` repeats (`?path=a&path=b`). More than one of them, or `archive=true` for a single one
 * (which is how a directory is downloaded), asks for a zip whose entries are named relative to
 * `base` — by default the directory the first path is in. `inline=true` asks for a preview instead,
 * and only ever gets one for a single file whose extension is on [InlinePreviewTypes].
 *
 * The response is written by the node's request, so this handler returns null — there is nothing
 * left for the framework to send.
 */
@Endpoint
class PanelDownloadServerFileAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val transferTicketStore: TransferTicketStore
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/files/download", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .queryParameter(explodedParam("path", arraySchema().items(stringSchema())))
            .queryParameter(optionalParam("archive", booleanSchema()))
            .queryParameter(optionalParam("base", stringSchema()))
            .queryParameter(optionalParam("inline", booleanSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val requested = parameters.queryParameter("path")?.jsonArray?.map { it?.toString() }.orEmpty()

        if (requested.isEmpty() || requested.size > ManagedServerFileClient.MAX_PATHS) {
            throw PathDenied()
        }

        val paths = requested.map { ManagedServerFileClient.normalisePath(it) }.distinct()

        if (paths.any { it.isEmpty() }) {
            throw PathDenied()
        }

        val archive = parameters.queryParameter("archive")?.boolean == true || paths.size > 1
        val inline = parameters.queryParameter("inline")?.boolean == true

        val base = if (archive) {
            parameters.queryParameter("base")?.string
                ?.let { ManagedServerFileClient.normalisePath(it) }
                ?: defaultBase(paths.first())
        } else {
            null
        }

        if (base != null && paths.any { !isInside(base, it) }) {
            throw PathDenied()
        }

        // A preview is one file the browser can show without running anything, and nothing else:
        // not a zip, not a directory, and never a type the allowlist has not already vouched for.
        val previewType = if (inline) {
            if (archive) {
                throw BadRequest()
            }

            InlinePreviewTypes.contentTypeOf(paths.first()) ?: throw BadRequest()
        } else {
            null
        }

        val path = paths.first()

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val ticket = transferTicketStore.issue(
            userId = userId,
            serverId = id,
            nodeId = target.nodeId,
            serverUuid = target.serverUuid,
            path = base ?: path,
            direction = TransferDirection.DOWNLOAD,
            fileName = if (base != null) archiveFileName(paths, base) else ManagedServerFileClient.fileNameOf(path),
            browserResponse = context.response(),
            contentType = if (base != null) ARCHIVE_CONTENT_TYPE else previewType,
            inline = previewType != null
        )

        // Fire and forget: the answer to "did it work" is the node's own HTTP request arriving,
        // and waiting for a socket reply as well would mean two things to time out instead of one.
        val message = if (base != null) {
            TransferPullMessage(ticket.id, target.serverUuid, base, paths)
        } else {
            TransferPullMessage(ticket.id, target.serverUuid, path)
        }

        fileClient.send(target, message)

        paths.forEach { downloaded ->
            fileClient.log(context, id, ServerFileChangedLog.ACTION_DOWNLOAD, downloaded, sqlClient = sqlClient)
        }

        val written = withTimeoutOrNull(TransferTicketStore.TTL_MS) {
            try {
                ticket.completion.await()
            } catch (exception: Exception) {
                transferTicketStore.discard(ticket)

                throw FileOperationFailed(extras = mapOf("message" to (exception.message ?: "The transfer failed.")))
            }
        }

        transferTicketStore.discard(ticket)

        if (written == null) {
            throw TransferExpired()
        }

        return null
    }

    companion object {
        const val ARCHIVE_CONTENT_TYPE = "application/zip"

        /** What a zip of a selection made at the server root is called, since the root has no name. */
        const val ROOT_ARCHIVE_NAME = "files"

        /** The directory [path] is in, which is where a zip's entry names start by default. */
        fun defaultBase(path: String): String = path.substringBeforeLast('/', "")

        /** Whether [path] lies strictly inside [base]; everything but the root itself is inside the root. */
        fun isInside(base: String, path: String): Boolean =
            if (base.isEmpty()) path.isNotEmpty() else path.startsWith("$base/")

        /**
         * The name a zip download is saved under.
         *
         * One path is named after itself — downloading `world` gives `world.zip`. Several are named
         * after the directory they were picked from, because that is what the person was looking
         * at when they picked them, and `files.zip` when that directory is the root.
         */
        fun archiveFileName(paths: List<String>, base: String): String {
            val name = if (paths.size == 1) {
                ManagedServerFileClient.fileNameOf(paths.first())
            } else {
                base.substringAfterLast('/').ifEmpty { ROOT_ARCHIVE_NAME }
            }

            return "$name.zip"
        }
    }
}
