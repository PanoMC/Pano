package com.panomc.node.host

import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * How much disk a managed server's directory takes, measured late and remembered (§2.4.18 A).
 *
 * There is no cheap way to ask this: the only answer is to walk the directory and add the files
 * up, and a server with a forty gigabyte world is tens of thousands of files. So the walk never
 * happens on the thread that is reporting metrics -- the tick asks [bytesOf], gets whatever was
 * measured last (null until the first walk finishes, which the panel draws as a dash) and moves
 * on, while the walk itself runs on Vert.x's worker pool and writes its result into the cache for
 * the tick after it.
 *
 * One walk per server at a time, and one every five minutes at most: a directory's size does not
 * move fast enough to be worth measuring every ten seconds, and a walk that outlives its interval
 * must not stack up behind itself.
 *
 * [invalidate] is the other half of the same bargain. The size *does* move all at once when a
 * backup is restored, a server is imported or reinstalled, or its files are deleted, and waiting
 * out the five minutes would leave the panel showing a figure that is not merely stale but wrong.
 */
class ServerDiskUsage(
    private val logger: NodeLogger,
    /** Runs one measurement somewhere that is allowed to block. */
    private val offload: (() -> Unit) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    constructor(vertx: Vertx, logger: NodeLogger) : this(
        logger,
        // `false` -- unordered: two servers' walks are independent and queueing them behind each
        // other would make the slowest directory on the host decide when every other one is
        // measured.
        { work -> vertx.executeBlocking<Void>({ work(); null }, false) }
    )

    private data class Measurement(val bytes: Long, val at: Long)

    private val measurements = ConcurrentHashMap<String, Measurement>()

    private val walking = ConcurrentHashMap.newKeySet<String>()

    /**
     * The last measured size of [uuid]'s directory, starting a fresh walk when the one on record
     * has aged out.
     *
     * Never blocks and never returns something it has not actually measured: a server nobody has
     * walked yet reports null, and a server whose walk is running right now reports the previous
     * figure rather than waiting for the new one.
     */
    fun bytesOf(uuid: String, directory: File): Long? {
        val known = measurements[uuid]

        if (known == null || clock() - known.at >= CACHE_MILLIS) {
            walk(uuid, directory)
        }

        return known?.bytes
    }

    /**
     * Forgets what [uuid]'s directory measured, so the next tick starts a fresh walk.
     *
     * The figure is dropped rather than kept until the new one lands, because the events that call
     * this are exactly the ones that make the old number a lie -- a restored backup, a reinstall
     * or a delete. One tick of "not known yet" is honest; a stale gigabyte count is not.
     */
    fun invalidate(uuid: String) {
        measurements.remove(uuid)
    }

    private fun walk(uuid: String, directory: File) {
        // Whoever is already walking this server will publish a fresh figure; a second walk would
        // only read the same directory twice.
        if (!walking.add(uuid)) {
            return
        }

        try {
            offload {
                try {
                    measurements[uuid] = Measurement(directorySize(directory), clock())
                } catch (throwable: Throwable) {
                    logger.warn("Measuring the size of $uuid failed: ${throwable.message}")
                } finally {
                    walking.remove(uuid)
                }
            }
        } catch (throwable: Throwable) {
            // Nothing is going to clear the mark if the work never ran, and a node that could not
            // hand one walk to its worker pool must still be able to try on the next tick.
            walking.remove(uuid)

            logger.warn("Could not start the size measurement of $uuid: ${throwable.message}")
        }
    }

    companion object {
        /** How long a measurement is good for. */
        const val CACHE_MILLIS: Long = 5L * 60L * 1000L

        /**
         * Total size of the partition [directory] sits on, or null when it cannot be read.
         *
         * The other half of the disk gauge: a number of bytes means nothing on its own, and
         * "41 GB of 500 GB" is the reading an operator can act on. Unlike the size of the
         * directory this costs one `statvfs` and no walk at all, so it is asked on every tick and
         * never cached. A path that does not exist, or a host that will not answer, reports zero
         * — which is not a disk of no size, so it goes up as null.
         */
        fun totalSpace(directory: File): Long? = try {
            directory.totalSpace.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        }

        /**
         * Bytes held by the regular files under [directory], symbolic links not followed.
         *
         * Not following links is what keeps the number the server's own: a world symlinked in from
         * an SSD, or the whole directory linked from somewhere else, belongs to whatever it points
         * at and counting it here would bill it to this server as well. Files that vanish while the
         * walk is running -- a rotated log, a temporary file the server just removed -- are skipped
         * rather than raised, because a server writing to its own directory is the normal case and
         * losing the whole measurement over one of them would mean never measuring a busy server.
         */
        fun directorySize(directory: File): Long {
            val root = directory.toPath()

            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return 0L
            }

            var total = 0L

            Files.walkFileTree(root, emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile) {
                        total += attributes.size()
                    }

                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE

                override fun postVisitDirectory(directory: Path, exception: IOException?): FileVisitResult =
                    FileVisitResult.CONTINUE
            })

            return total
        }
    }
}
