package com.panomc.platform.backup.remote

import com.panomc.platform.archive.instance.InstanceLayout
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * How much a transfer would carry, for the panel's transfer dialog: the uncompressed size of the files a plain
 * archive includes (the [InstanceLayout.trees] rules, plus `config.conf`) and the database's data + index length.
 * An estimate, not the archive size. Calculated in the background (one at a time, on the IO dispatcher), kept for
 * [ttlMs]; [get] answers at once.
 */
class TransferEstimate(
    private val scope: CoroutineScope,
    private val layout: () -> InstanceLayout,
    /** Data + index bytes of this Pano's tables, or null when the database cannot say. */
    private val databaseBytes: suspend () -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
    private val logger: Logger? = null
) {
    private data class Result(val bytes: Long, val files: Long, val calculatedAt: Long)

    @Volatile
    private var result: Result? = null

    @Volatile
    private var running = false

    /** When the last calculation failed; it is not retried within [ttlMs]. */
    @Volatile
    private var failedAt: Long? = null

    /** `{status: CALCULATING}`, `{status: DONE, bytes, files, calculatedAt}` or `{status: FAILED}`; starts a calculation when none is fresh. */
    @Synchronized
    fun get(): JsonObject {
        val current = result

        if (current != null && clock() - current.calculatedAt < ttlMs) {
            return done(current)
        }

        val failure = failedAt

        if (current == null && failure != null && clock() - failure < ttlMs) {
            return JsonObject().put("status", FAILED)
        }

        if (!running) {
            running = true
            failedAt = null

            scope.launch { calculate() }
        }

        // A stale figure is better than none while the new one is being made.
        return current?.let { done(it) } ?: JsonObject().put("status", CALCULATING)
    }

    private fun done(value: Result) =
        JsonObject().put("status", DONE).put("bytes", value.bytes).put("files", value.files).put("calculatedAt", value.calculatedAt)

    private suspend fun calculate() {
        try {
            val layout = layout()

            val (bytes, files) = withContext(Dispatchers.IO) { walk(layout) }
            val database = runCatching { databaseBytes() }.getOrNull() ?: 0L

            synchronized(this) { result = Result(bytes + database, files, clock()) }
        } catch (e: Throwable) {
            logger?.warn("Could not estimate the size of this Pano: ${e.message}")

            synchronized(this) { failedAt = clock() }
        } finally {
            synchronized(this) { running = false }
        }
    }

    /** Regular files only, symlinks not followed (as the archiver does), the excluded folders pruned. */
    private fun walk(layout: InstanceLayout): Pair<Long, Long> {
        var bytes = 0L
        var files = 0L

        layout.configFile.takeIf { it.isFile }?.let {
            bytes += it.length()
            files++
        }

        layout.trees.forEach { tree ->
            val root = tree.dir.toPath()

            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@forEach

            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE

                    return if (tree.exclude(root.relativize(dir).joinToString("/"))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && !attrs.isSymbolicLink && !tree.exclude(root.relativize(file).joinToString("/"))) {
                        bytes += attrs.size()
                        files++
                    }

                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }

        return bytes to files
    }

    companion object {
        const val CALCULATING = "CALCULATING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val TTL_MS = 2L * 60 * 1000
    }
}
