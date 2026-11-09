package com.panomc.node.util

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes a file tree without ever following a link out of it.
 *
 * Kotlin's [File.deleteRecursively] walks into anything [File.isDirectory] says is a directory,
 * and that includes a symlink to one: it empties the link's target before it removes the link. A
 * server directory is written by the server itself (and bind-mounted into a container on the
 * Docker runtime), so a plugin can plant `plugins/cache -> /somewhere/else`, and an ordinary
 * delete in the panel would then wipe whatever that points at. Here a link — a symlink, or a
 * Windows junction — is removed as a link and its target is never touched.
 */
object SafeDelete {
    /**
     * Removes [file] and, when it is a real directory, everything under it. Like
     * [File.deleteRecursively], true when nothing is left, including when nothing was there.
     */
    fun tree(file: File): Boolean = tree(file.toPath())

    fun tree(root: Path): Boolean {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return true
        }

        var ok = true

        try {
            // No FOLLOW_LINKS: a symlink, whatever it points at, arrives in visitFile as itself.
            Files.walkFileTree(root, emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // A Windows junction reads as a directory that is also "other": drop the
                    // junction itself, never what it leads to.
                    if (attrs.isOther || Files.isSymbolicLink(dir)) {
                        ok = remove(dir) && ok

                        return FileVisitResult.SKIP_SUBTREE
                    }

                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ok = remove(file) && ok

                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    ok = remove(file) && ok

                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    ok = remove(dir) && ok

                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            ok = false
        }

        return ok && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    private fun remove(path: Path): Boolean = try {
        Files.deleteIfExists(path)

        true
    } catch (_: NoSuchFileException) {
        true
    } catch (_: IOException) {
        false
    }
}
