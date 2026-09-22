package com.panomc.node.files

import com.panomc.node.net.FileRequestMessage
import com.panomc.node.net.NodeProtocol
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.FileHash
import com.panomc.node.util.Murmur2
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Every `FILE_*` request, answered from inside one server's directory and nowhere else.
 *
 * Each operation resolves its path through [PathSafety.resolveRelative], which rejects traversal,
 * absolute paths and symlinks that leave the directory, and then through [ServerFileDenylist],
 * which hides this server's own credentials. Both checks happen here rather than at the call
 * sites: a new operation that forgets one of them would be a sandbox escape, so there is exactly
 * one door.
 *
 * Nothing throws out of [handle]. Everything a caller could have done wrong comes back as
 * `{ ok: false, error }`, because the other end of this is a panel showing a message, not a
 * daemon that should fall over.
 */
class FileService(
    private val registry: ServerRegistry,
    private val logger: NodeLogger
) {
    /** Answers one request, as the payload of the `FILE_RESULT` the caller sends back. */
    fun handle(event: String, message: FileRequestMessage): JsonObject {
        val server = registry.get(message.serverUuid)
            ?: return failure(ERROR_UNKNOWN_SERVER)

        val root = server.directory

        return try {
            when (event) {
                NodeProtocol.Inbound.FILE_LIST -> list(root, message.path)
                NodeProtocol.Inbound.FILE_READ -> read(root, message.path, message.maxBytes)
                NodeProtocol.Inbound.FILE_WRITE -> write(root, message.path, message.content)
                NodeProtocol.Inbound.FILE_MKDIR -> mkdir(root, message.path)
                NodeProtocol.Inbound.FILE_DELETE -> delete(root, message.paths)
                NodeProtocol.Inbound.FILE_RENAME -> rename(root, message.from, message.to)
                NodeProtocol.Inbound.FILE_ARCHIVE -> archive(root, message.paths, message.target)
                NodeProtocol.Inbound.FILE_UNARCHIVE -> unarchive(root, message.path, message.target)
                NodeProtocol.Inbound.FILE_CHMOD -> chmod(root, message.path, message.mode)
                NodeProtocol.Inbound.FILE_HASHES -> hashes(root, message.path, message.names)
                else -> failure(ERROR_UNKNOWN_OPERATION)
            }
        } catch (exception: IllegalArgumentException) {
            // The only thing that throws this is a path check, and it is not worth logging an
            // operator's typo at warn level every time.
            failure(ERROR_PATH_DENIED)
        } catch (exception: Exception) {
            logger.warn("$event on ${server.uuid} failed: ${exception.message}")

            failure(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private fun list(root: File, path: String?): JsonObject {
        val directory = resolve(root, path)

        if (!directory.isDirectory) {
            return failure(ERROR_NOT_FOUND)
        }

        val relative = ServerFileDenylist.normalise(path)
        val entries = JsonArray()

        val children = directory.listFiles() ?: return failure(ERROR_NOT_FOUND)

        children
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { child ->
                val childPath = if (relative.isEmpty()) child.name else "$relative/${child.name}"

                if (ServerFileDenylist.isDenied(childPath)) {
                    return@forEach
                }

                if (entries.size() >= MAX_ENTRIES) {
                    return@forEach
                }

                entries.add(describe(child))
            }

        return success()
            .put("path", relative)
            .put("entries", entries)
            .put("truncated", children.size > entries.size())
    }

    private fun describe(file: File): JsonObject {
        val symlink = Files.isSymbolicLink(file.toPath())

        val type = when {
            symlink -> "symlink"
            file.isDirectory -> "dir"
            else -> "file"
        }

        val entry = JsonObject()
            .put("name", file.name)
            .put("type", type)
            .put("size", if (file.isDirectory) 0L else file.length())
            .put("modified", file.lastModified())

        mode(file)?.let { entry.put("mode", it) }

        return entry
    }

    private fun read(root: File, path: String?, maxBytes: Int?): JsonObject {
        val file = resolve(root, path)

        if (!file.isFile) {
            return failure(ERROR_NOT_FOUND)
        }

        val limit = (maxBytes ?: DEFAULT_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
        val size = file.length()

        val bytes = file.inputStream().use { input ->
            input.readNBytes(limit)
        }

        // Decoded strictly so a jar or a world file comes back flagged rather than as a screenful
        // of replacement characters an editor would then happily save back over it.
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        if (decoded == null) {
            return success()
                .put("path", ServerFileDenylist.normalise(path))
                .put("content", "")
                .put("size", size)
                .put("truncated", size > limit)
                .put("binary", true)
        }

        return success()
            .put("path", ServerFileDenylist.normalise(path))
            .put("content", decoded)
            .put("size", size)
            .put("truncated", size > limit)
            .put("binary", false)
    }

    private fun write(root: File, path: String?, content: String?): JsonObject {
        val text = content ?: ""
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (bytes.size > MAX_WRITE_BYTES) {
            return failure(ERROR_TOO_LARGE)
        }

        val file = resolveMutable(root, path)

        if (file.isDirectory) {
            return failure(ERROR_NOT_A_FILE)
        }

        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        return success().put("size", bytes.size.toLong())
    }

    private fun mkdir(root: File, path: String?): JsonObject {
        val directory = resolveMutable(root, path)

        if (directory.isDirectory) {
            return success()
        }

        if (!directory.mkdirs()) {
            return failure(ERROR_NOT_CREATED)
        }

        return success()
    }

    private fun delete(root: File, paths: List<String>?): JsonObject {
        val requested = paths.orEmpty().filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            return failure(ERROR_NOTHING_SELECTED)
        }

        if (requested.size > MAX_BATCH) {
            return failure(ERROR_TOO_MANY)
        }

        var removed = 0

        requested.forEach { path ->
            val file = resolveMutable(root, path)

            if (!file.exists()) {
                return@forEach
            }

            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()

            if (!ok) {
                throw IllegalStateException("${ServerFileDenylist.normalise(path)} could not be removed.")
            }

            removed++
        }

        return success().put("removed", removed)
    }

    private fun rename(root: File, from: String?, to: String?): JsonObject {
        val source = resolveMutable(root, from)
        val target = resolveMutable(root, to)

        if (!source.exists()) {
            return failure(ERROR_NOT_FOUND)
        }

        if (target.exists()) {
            return failure(ERROR_EXISTS)
        }

        target.parentFile?.mkdirs()

        if (!source.renameTo(target)) {
            return failure(ERROR_NOT_MOVED)
        }

        return success()
    }

    private fun archive(root: File, paths: List<String>?, target: String?): JsonObject {
        val requested = paths.orEmpty().filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            return failure(ERROR_NOTHING_SELECTED)
        }

        // Every source is resolved (and denied where it has to be) before a single byte is read.
        requested.forEach { resolve(root, it) }

        val archiveFile = resolveMutable(root, target)

        if (archiveFile.exists()) {
            return failure(ERROR_EXISTS)
        }

        val written = ZipTool.archive(root, requested, archiveFile)

        return success().put("size", written).put("archiveSize", archiveFile.length())
    }

    private fun unarchive(root: File, path: String?, target: String?): JsonObject {
        val archiveFile = resolve(root, path)

        if (!archiveFile.isFile) {
            return failure(ERROR_NOT_FOUND)
        }

        if (!ZipTool.isZip(archiveFile)) {
            return failure(ERROR_NOT_AN_ARCHIVE)
        }

        val directory = resolveMutable(root, target)

        ZipTool.extract(root, archiveFile, directory)

        return success()
    }

    private fun chmod(root: File, path: String?, mode: String?): JsonObject {
        val file = resolveMutable(root, path)

        if (!file.exists()) {
            return failure(ERROR_NOT_FOUND)
        }

        val requested = mode?.trim().orEmpty()

        if (!requested.matches(MODE_PATTERN)) {
            return failure(ERROR_BAD_MODE)
        }

        val view = Files.getFileAttributeView(
            file.toPath(),
            java.nio.file.attribute.PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS
        ) ?: return failure(ERROR_NO_POSIX)

        view.setPermissions(PosixFilePermissions.fromString(toSymbolic(requested)))

        return success().put("mode", requested)
    }

    /**
     * The hashes of the named jars in one directory.
     *
     * Only ever the names Pano asked about, and only jars: this exists so Pano can ask a directory
     * "what are these files" and get an answer three plugin sites can look up, not so a panel can
     * walk a server hashing whatever it likes. A name that is not a jar, is not a single path
     * segment or is not there is simply absent from the answer, because a batch that failed
     * because one plugin was deleted a second ago would be a worse answer than one row short.
     *
     * Three hashes rather than one, because the three sites disagree: Modrinth looks files up by
     * SHA-1, CurseForge by its own murmur2 fingerprint, and SHA-512 is what Modrinth publishes on
     * the version it hands back. All three are computed in one pass over the directory so the
     * jars are read once rather than three times.
     */
    private fun hashes(root: File, path: String?, names: List<String>?): JsonObject {
        val requested = names.orEmpty().filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            return failure(ERROR_NOTHING_SELECTED)
        }

        if (requested.size > MAX_HASHES) {
            return failure(ERROR_TOO_MANY)
        }

        // The directory is resolved first, so a denied or escaping path fails once rather than
        // once per name.
        val directory = resolve(root, path)

        if (!directory.isDirectory) {
            return failure(ERROR_NOT_FOUND)
        }

        val prefix = ServerFileDenylist.normalise(path)
        val files = JsonArray()

        requested.distinct().forEach { name ->
            if (!PathSafety.isSafeSegment(name) || !name.lowercase().endsWith(JAR_SUFFIX)) {
                return@forEach
            }

            val relative = if (prefix.isEmpty()) name else "$prefix/$name"

            val file = try {
                resolve(root, relative)
            } catch (_: IllegalArgumentException) {
                return@forEach
            }

            if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
                return@forEach
            }

            val entry = JsonObject()
                .put("name", name)
                .put("size", file.length())
                .put("sha1", hashOrNull(file, "SHA-1"))
                .put("sha512", hashOrNull(file, "SHA-512"))
                .put("murmur2", Murmur2.fingerprint(file))

            files.add(entry)
        }

        return success().put("path", prefix).put("files", files)
    }

    private fun hashOrNull(file: File, algorithm: String): String? = try {
        FileHash.of(file, algorithm)
    } catch (_: Exception) {
        null
    }

    /** Resolves a path for a read-only operation. */
    private fun resolve(root: File, path: String?): File {
        if (ServerFileDenylist.isDenied(path)) {
            throw IllegalArgumentException(ERROR_PATH_DENIED)
        }

        return PathSafety.resolveRelative(root, path)
    }

    /** Resolves a path for an operation that changes or removes something. */
    private fun resolveMutable(root: File, path: String?): File {
        if (!ServerFileDenylist.isMutable(path)) {
            throw IllegalArgumentException(ERROR_PATH_DENIED)
        }

        return PathSafety.resolveRelative(root, path)
    }

    private fun mode(file: File): String? = try {
        val permissions = Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)

        toOctal(PosixFilePermissions.toString(permissions))
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_READ_BYTES = 262_144
        const val MAX_READ_BYTES = 1024 * 1024
        const val MAX_WRITE_BYTES = 1024 * 1024
        const val MAX_ENTRIES = 5_000
        const val MAX_BATCH = 500

        /** Most jars one `FILE_HASHES` request may name; hashing is the one operation here that reads whole files. */
        const val MAX_HASHES = 200

        private const val JAR_SUFFIX = ".jar"

        const val ERROR_PATH_DENIED = "PATH_DENIED"
        const val ERROR_NOT_FOUND = "NOT_FOUND"
        const val ERROR_TOO_LARGE = "TOO_LARGE"
        const val ERROR_EXISTS = "ALREADY_EXISTS"
        const val ERROR_UNKNOWN_SERVER = "UNKNOWN_SERVER"
        const val ERROR_UNKNOWN_OPERATION = "UNKNOWN_OPERATION"
        const val ERROR_NOT_A_FILE = "NOT_A_FILE"
        const val ERROR_NOT_AN_ARCHIVE = "NOT_AN_ARCHIVE"
        const val ERROR_NOT_CREATED = "NOT_CREATED"
        const val ERROR_NOT_MOVED = "NOT_MOVED"
        const val ERROR_NOTHING_SELECTED = "NOTHING_SELECTED"
        const val ERROR_TOO_MANY = "TOO_MANY"
        const val ERROR_BAD_MODE = "BAD_MODE"
        const val ERROR_NO_POSIX = "NO_POSIX"

        private val MODE_PATTERN = Regex("^[0-7]{3}$")

        fun success(): JsonObject = JsonObject().put("ok", true)

        fun failure(error: String): JsonObject = JsonObject().put("ok", false).put("error", error)

        /** `755` as the `rwxr-xr-x` [PosixFilePermissions] wants. */
        fun toSymbolic(octal: String): String = octal.map { digit ->
            val bits = digit - '0'

            StringBuilder()
                .append(if (bits and 4 != 0) 'r' else '-')
                .append(if (bits and 2 != 0) 'w' else '-')
                .append(if (bits and 1 != 0) 'x' else '-')
                .toString()
        }.joinToString("")

        /** `rwxr-xr-x` back to `755`, for what a listing reports. */
        fun toOctal(symbolic: String): String = symbolic.chunked(3).joinToString("") { part ->
            var bits = 0

            if (part.getOrNull(0) == 'r') bits += 4
            if (part.getOrNull(1) == 'w') bits += 2
            if (part.getOrNull(2) == 'x') bits += 1

            bits.toString()
        }
    }
}
