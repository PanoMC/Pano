package com.panomc.node.backup

import com.panomc.node.files.ServerFileDenylist
import com.panomc.node.util.PathSafety
import java.io.File
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Empties a world directory before a restore writes the backup's copy of it back.
 *
 * A restore used to lay the backup over whatever was there, which is right for a config folder and
 * wrong for a world: every region file generated after the backup was taken survived it, so the
 * restored world was yesterday's `level.dat` and yesterday's spawn stitched to this morning's
 * outskirts — chunks that reference entities, maps and player positions the rest of the world has
 * never heard of. A world is one consistent thing, so it is replaced as one.
 *
 * Only the directory's *contents* go, and never a denylisted path: the directory itself stays
 * (its permissions and owner are whatever the operator set up), and a symbolic link inside it is
 * removed as a link without ever following it anywhere.
 */
object WorldReplacement {
    /** Deletes everything inside `serverDirectory/[world]`, which must be a top-level directory name. */
    fun clear(serverDirectory: File, world: String) {
        val name = ServerFileDenylist.normalise(world)

        if (name.isEmpty() || name.contains('/') || !PathSafety.isSafeSegment(name) || !ServerFileDenylist.isMutable(name)) {
            return
        }

        val directory = PathSafety.resolveRelative(serverDirectory, name)
        val path = directory.toPath()

        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return
        }

        directory.listFiles()?.forEach { child -> delete(child, "$name/${child.name}") }
    }

    private fun delete(file: File, relative: String) {
        if (!ServerFileDenylist.isMutable(relative)) {
            return
        }

        val path = file.toPath()

        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)

            return
        }

        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            file.listFiles()?.forEach { child -> delete(child, "$relative/${child.name}") }

            try {
                Files.deleteIfExists(path)
            } catch (_: DirectoryNotEmptyException) {
                // Something in it was kept on purpose, so the directory that holds it stays too.
            }

            return
        }

        Files.deleteIfExists(path)
    }
}
