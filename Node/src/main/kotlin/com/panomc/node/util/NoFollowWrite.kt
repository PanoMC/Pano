package com.panomc.node.util

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Writes the daemon's own files inside a server directory without following a link.
 *
 * The server itself can write that directory, so a file the daemon appends to may have been
 * swapped for a link to somewhere else on the host. The folder must be a real directory (not a
 * link or a junction) and the file is opened with `NOFOLLOW_LINKS`: a link there fails the write
 * instead of redirecting it.
 */
object NoFollowWrite {
    /** Creates [directory] when missing; throws when it is anything but a real directory. */
    fun ensureDirectory(directory: File) {
        val path = directory.toPath()

        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path)
        }

        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) {
            throw IOException("${directory.path} is not a plain directory.")
        }
    }

    fun append(file: File): OutputStream = Files.newOutputStream(
        file.toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
    )

    fun writeText(file: File, text: String) {
        Files.newOutputStream(
            file.toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
        ).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
