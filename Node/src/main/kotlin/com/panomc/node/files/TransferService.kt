package com.panomc.node.files

import com.panomc.node.config.NodeConfig
import com.panomc.node.net.TransferPullMessage
import com.panomc.node.net.TransferPushMessage
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import io.vertx.core.json.JsonObject
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Moves whole files between a server directory and a browser, without either end holding one.
 *
 * `FILE_READ`/`FILE_WRITE` carry their content inside a WebSocket frame, which is fine for a
 * `server.properties` and impossible for a 400 MB world. A transfer instead uses a one-shot ticket
 * and an ordinary HTTP body: Pano issues the ticket, tells this node about it over the socket, and
 * the node opens a plain request to Pano that streams the bytes. Nothing is buffered on either
 * side, and the ticket is what ties that anonymous-looking request back to the person who asked
 * for it.
 *
 * The direction names are from Pano's point of view: a PULL moves a file *out of* the server to a
 * waiting browser, a PUSH moves an uploaded file *into* it.
 */
class TransferService(
    private val registry: ServerRegistry,
    private val config: NodeConfig,
    private val logger: NodeLogger,
    /** Lets a virtual path such as `@backup/<id>` resolve to a file outside the server directory. */
    private val virtualSource: (serverUuid: String, path: String) -> File? = { _, _ -> null },
    /**
     * Lets a virtual path resolve to an archive that only exists while it is being streamed — a
     * snapshot backup, which is chunks and a manifest on disk and a zip only on the way out.
     * Asked before [virtualSource], and only for virtual paths.
     */
    private val virtualArchive: (serverUuid: String, path: String) -> VirtualArchive? = { _, _ -> null }
) {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** Streams a file up to Pano, which is pumping it into the browser that asked for it. */
    fun pull(message: TransferPullMessage) {
        val ticket = message.ticket?.takeIf { it.isNotBlank() } ?: return

        if (!message.paths.isNullOrEmpty()) {
            pullArchive(ticket, message)

            return
        }

        if (pullVirtualArchive(ticket, message)) {
            return
        }

        val source = try {
            resolveSource(message.serverUuid, message.path)
        } catch (exception: Exception) {
            logger.warn("Refused a transfer of ${message.path}: ${exception.message}")

            reportFailure(ticket, FileService.ERROR_PATH_DENIED)

            return
        }

        if (source == null || !source.isFile) {
            reportFailure(ticket, FileService.ERROR_NOT_FOUND)

            return
        }

        if (source.length() > MAX_TRANSFER_BYTES) {
            reportFailure(ticket, FileService.ERROR_TOO_LARGE)

            return
        }

        try {
            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(TRANSFER_TIMEOUT)
                .header("Authorization", "Bearer ${config.token}")
                .header("Content-Type", "application/octet-stream")
                .header(FILE_NAME_HEADER, source.name)
                .PUT(HttpRequest.BodyPublishers.ofFile(source.toPath()))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())

            if (response.statusCode() !in 200..299) {
                logger.warn("Pano answered ${response.statusCode()} to the upload of ${source.name}.")
            }
        } catch (exception: Exception) {
            logger.error("Could not send ${source.name} to Pano: ${exception.message}", exception)
        }
    }

    /**
     * Streams several files and directories up to Pano as one zip, built while it is being sent.
     *
     * Every top-level entry is checked exactly like a single pull before a byte goes out, because
     * until then a refusal can still be reported cleanly on the empty-PUT path. After that the only
     * way left to fail is to break the stream: the zip is written into a pipe on a thread of its
     * own, the PUT reads the other end, and anything that goes wrong halfway — the size ceiling,
     * a file that stopped being readable — closes the pipe under the request instead of letting
     * it end. Pano sees a broken upload and resets the browser's download, which is the honest
     * answer for an archive that is missing its end.
     *
     * No `Content-Length`, as nobody knows the size of a zip that does not exist yet; Pano pipes
     * it through chunked.
     */
    private fun pullArchive(ticket: String, message: TransferPullMessage) {
        val server = registry.get(message.serverUuid)

        if (server == null) {
            reportFailure(ticket, FileService.ERROR_NOT_FOUND)

            return
        }

        val base = ServerFileDenylist.normalise(message.path)
        val paths = message.paths.orEmpty().map { ServerFileDenylist.normalise(it) }.distinct()

        val refusal = validateArchive(server.directory, base, paths)

        if (refusal != null) {
            logger.warn("Refused an archive transfer of ${paths.size} path(s) under /$base: $refusal")

            reportFailure(ticket, refusal)

            return
        }

        val fileName = "${base.substringAfterLast('/').ifEmpty { ARCHIVE_ROOT_NAME }}.zip"

        streamZip(ticket, fileName) { output -> ZipStream.write(server.directory, base, paths, output) }
    }

    /**
     * Sends a virtual path that resolves to a streamed archive, and says whether it did.
     *
     * False means the path is not one — an ordinary file, or a FULL backup that is a real file —
     * and the caller goes on with the single-file pull. A snapshot larger than a transfer may carry
     * is refused up front, on its known total, instead of being cut off a gigabyte in.
     */
    private fun pullVirtualArchive(ticket: String, message: TransferPullMessage): Boolean {
        val requested = message.path.orEmpty()

        if (!requested.startsWith(VIRTUAL_PREFIX)) {
            return false
        }

        val server = registry.get(message.serverUuid) ?: return false

        val archive = try {
            virtualArchive(server.uuid, requested)
        } catch (exception: Exception) {
            logger.warn("Could not open $requested for a transfer: ${exception.message}")

            reportFailure(ticket, FileService.ERROR_NOT_FOUND)

            return true
        } ?: return false

        if (archive.sizeBytes > MAX_TRANSFER_BYTES) {
            reportFailure(ticket, FileService.ERROR_TOO_LARGE)

            return true
        }

        streamZip(ticket, archive.fileName) { output -> archive.write(output, MAX_TRANSFER_BYTES) }

        return true
    }

    /**
     * PUTs whatever [write] produces to Pano as a zip, through a pipe, while it is being produced.
     *
     * The writer runs on a thread of its own and the request reads the other end. Only a
     * finished archive closes the pipe normally; a writer that throws breaks it instead, so Pano
     * sees an upload that was cut off rather than a shorter archive that looks complete.
     */
    private fun streamZip(ticket: String, fileName: String, write: (java.io.OutputStream) -> Unit) {
        val pipedInput = PipedInputStream(BUFFER_SIZE)
        val pipedOutput = PipedOutputStream(pipedInput)

        val writer = Thread({
            try {
                write(pipedOutput)

                // Only a finished archive gets an end of stream; every other way out of here
                // leaves the pipe broken so the reader cannot mistake it for a complete one.
                pipedOutput.close()
            } catch (exception: Exception) {
                logger.warn("Stopped streaming an archive to Pano: ${exception.message}")

                runCatching { pipedInput.close() }
            }
        }, "pano-transfer-zip-$ticket")

        writer.isDaemon = true

        try {
            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(TRANSFER_TIMEOUT)
                .header("Authorization", "Bearer ${config.token}")
                .header("Content-Type", "application/zip")
                .header(FILE_NAME_HEADER, fileName)
                .PUT(HttpRequest.BodyPublishers.ofInputStream { pipedInput })
                .build()

            writer.start()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())

            if (response.statusCode() !in 200..299) {
                logger.warn("Pano answered ${response.statusCode()} to the archive $fileName.")
            }
        } catch (exception: Exception) {
            logger.warn("Could not send the archive $fileName to Pano: ${exception.message}")
        } finally {
            // If the request died first the writer would otherwise sit on a full pipe forever;
            // closing the read end makes its next write throw and lets the thread go.
            runCatching { pipedInput.close() }
        }
    }

    /**
     * Downloads what the browser uploaded and writes it into the server, reporting the outcome.
     *
     * The reply is what the panel's upload request is waiting on, so every path out of here
     * produces one.
     */
    fun push(message: TransferPushMessage): JsonObject {
        val ticket = message.ticket?.takeIf { it.isNotBlank() }
            ?: return FileService.failure(FileService.ERROR_NOT_FOUND)

        val server = registry.get(message.serverUuid)
            ?: return FileService.failure(FileService.ERROR_UNKNOWN_SERVER)

        if (!ServerFileDenylist.isMutable(message.path)) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        val declared = message.size ?: 0L

        if (declared > MAX_TRANSFER_BYTES) {
            return FileService.failure(FileService.ERROR_TOO_LARGE)
        }

        val target = try {
            PathSafety.resolveRelative(server.directory, message.path)
        } catch (_: Exception) {
            return FileService.failure(FileService.ERROR_PATH_DENIED)
        }

        if (target.isDirectory) {
            return FileService.failure(FileService.ERROR_NOT_A_FILE)
        }

        // Written next to the destination and moved into place, so a transfer that dies halfway
        // does not leave a half-written jar where a server will try to load one.
        val temporary = File(target.parentFile, "${target.name}$PARTIAL_SUFFIX")

        return try {
            target.parentFile?.mkdirs()

            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(TRANSFER_TIMEOUT)
                .header("Authorization", "Bearer ${config.token}")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                response.body().close()

                return FileService.failure("Pano answered HTTP ${response.statusCode()}.")
            }

            var copied = 0L

            response.body().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)

                    while (true) {
                        val read = input.read(buffer)

                        if (read <= 0) {
                            break
                        }

                        copied += read

                        if (copied > MAX_TRANSFER_BYTES) {
                            throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                        }

                        output.write(buffer, 0, read)
                    }
                }
            }

            target.delete()

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            FileService.success().put("size", copied)
        } catch (exception: Exception) {
            temporary.delete()

            logger.warn("Could not store an upload into ${server.uuid}: ${exception.message}")

            FileService.failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    /**
     * Downloads whatever is spooled under [ticket] straight into [target].
     *
     * The same GET as [push], without a server directory in the picture: an import's archive has
     * nowhere to land yet, because the directory it will become is what the archive is for.
     * Returns how many bytes arrived.
     */
    fun fetch(ticket: String, target: File, onProgress: (Long) -> Unit = {}): Long {
        val request = HttpRequest.newBuilder(uri(ticket))
            .timeout(TRANSFER_TIMEOUT)
            .header("Authorization", "Bearer ${config.token}")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
            response.body().close()

            throw IllegalStateException("Pano answered HTTP ${response.statusCode()} for this upload.")
        }

        target.parentFile?.mkdirs()

        var copied = 0L

        response.body().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)

                while (true) {
                    val read = input.read(buffer)

                    if (read <= 0) {
                        break
                    }

                    copied += read

                    if (copied > MAX_TRANSFER_BYTES) {
                        throw IllegalStateException(FileService.ERROR_TOO_LARGE)
                    }

                    output.write(buffer, 0, read)

                    onProgress(copied)
                }
            }
        }

        return copied
    }

    /**
     * Tells Pano this transfer will never arrive.
     *
     * Sent as an empty PUT with an error header rather than over the socket: the browser is
     * blocked on that exact HTTP exchange, and failing it there is what releases it — a message on
     * the socket would have to find its way back to the same request anyway.
     */
    private fun reportFailure(ticket: String, error: String) {
        try {
            val request = HttpRequest.newBuilder(uri(ticket))
                .timeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer ${config.token}")
                .header(ERROR_HEADER, error)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build()

            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (exception: Exception) {
            logger.warn("Could not report a failed transfer to Pano: ${exception.message}")
        }
    }

    private fun resolveSource(serverUuid: String?, path: String?): File? {
        val server = registry.get(serverUuid) ?: return null
        val requested = path.orEmpty()

        if (requested.startsWith(VIRTUAL_PREFIX)) {
            return virtualSource(server.uuid, requested)
        }

        if (ServerFileDenylist.isDenied(requested)) {
            throw IllegalArgumentException(FileService.ERROR_PATH_DENIED)
        }

        return PathSafety.resolveRelative(server.directory, requested)
    }

    private fun uri(ticket: String): URI {
        require(PathSafety.isSafeSegment(ticket)) { "Rejected an unusable transfer ticket." }

        return URI.create("${config.platformUrl.trimEnd('/')}$TRANSFER_PATH$ticket")
    }

    companion object {
        /** Ceiling for one transfer, in either direction. */
        const val MAX_TRANSFER_BYTES = 1024L * 1024 * 1024

        const val TRANSFER_PATH = "/api/node/transfer/"

        /** Set on an empty PUT to fail the browser request waiting on this ticket. */
        const val ERROR_HEADER = "X-Pano-Transfer-Error"

        /** Lets Pano name the download without having to trust the browser's own path. */
        const val FILE_NAME_HEADER = "X-Pano-File-Name"

        /** Paths that are not inside the server directory, such as `@backup/<id>`. */
        const val VIRTUAL_PREFIX = "@"

        /** Names the archive of a selection made at the server root, which has no name of its own. */
        const val ARCHIVE_ROOT_NAME = "files"

        /**
         * Why an archive of [paths] under [base] may not be sent, or null when it may.
         *
         * The same answers a single pull gives, per top-level entry: a denied path, a virtual one
         * or one outside [base] is [FileService.ERROR_PATH_DENIED], a missing one is
         * [FileService.ERROR_NOT_FOUND]. Only the top level is checked here; what the walk finds
         * below it is filtered as it goes, by [ZipStream].
         */
        fun validateArchive(serverDirectory: File, base: String, paths: List<String>): String? {
            if (paths.isEmpty() || base.startsWith(VIRTUAL_PREFIX) || ServerFileDenylist.isDenied(base)) {
                return FileService.ERROR_PATH_DENIED
            }

            paths.forEach { path ->
                val inside = if (base.isEmpty()) path.isNotEmpty() else path.startsWith("$base/")

                if (!inside || path.startsWith(VIRTUAL_PREFIX) || ServerFileDenylist.isDenied(path)) {
                    return FileService.ERROR_PATH_DENIED
                }

                val source = try {
                    PathSafety.resolveRelative(serverDirectory, path)
                } catch (_: Exception) {
                    return FileService.ERROR_PATH_DENIED
                }

                if (!source.exists()) {
                    return FileService.ERROR_NOT_FOUND
                }
            }

            return null
        }

        private const val PARTIAL_SUFFIX = ".pano-part"
        private const val BUFFER_SIZE = 64 * 1024

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val TRANSFER_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
