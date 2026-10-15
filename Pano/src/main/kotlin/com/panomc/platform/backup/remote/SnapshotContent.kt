package com.panomc.platform.backup.remote

import com.panomc.platform.archive.ArchivePaths
import com.panomc.platform.archive.instance.InstanceLayout
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * What a snapshot of this Pano reads (see [com.panomc.platform.backup.PanoBackupRunner.prepareSnapshot]): the
 * directories of the [layout], and [dump], the uncompressed database dump taken just before (null = no database).
 */
data class SnapshotContent(
    val layout: InstanceLayout,
    val dbPrefix: String,
    val panoVersion: String,
    val schemeVersions: Map<String, Int>,
    val dumpTool: String?,
    val dump: File?
)

/**
 * The content layout of a Pano snapshot, the same one a hosted instance's snapshot has (`archive-format.md` §7.3), so
 * the two are interchangeable: `app/` tree = the files a `.panoarc` instance archive holds, `db/dump.sql` = the dump
 * uncompressed (so it deduplicates), `meta/pano.json` = what the archive manifest's `pano` / `db` carry.
 */
object SnapshotArc {
    const val DUMP_PATH = "db/dump.sql"
    const val META_PATH = "meta/pano.json"
    val ROOTS = listOf("app", "db", "meta")
    val EXCLUDE = listOf("app/file-uploads/cache", "app/file-uploads/temp", "app/file-uploads/transfer", "app/themes/**/.pano-license.jwt")

    fun meta(content: SnapshotContent, producer: String, skipped: List<String>): JsonObject = JsonObject()
        .put("panoVersion", content.panoVersion)
        .put("dbPrefix", content.dbPrefix)
        .put("schemeVersions", JsonObject(content.schemeVersions.toMutableMap<String, Any>()))
        .put("dumpTool", content.dumpTool)
        .put("producer", producer)
        .put("skipped", JsonArray(skipped))
}

/** A file the snapshot reads and the path it has inside it. */
class SnapshotFile(val path: String, val file: File)

/**
 * The files of an instance in the order a `pano-instance` archive writes them and under the same rules (no symlink is
 * followed, they are listed in [skipped]; theme license tokens and the uploads' cache / temp / transfer are left out).
 */
class SnapshotFiles(layout: InstanceLayout) {
    val files = ArrayList<SnapshotFile>()

    /** Every directory met (path → modified), empty ones too. */
    val directories = LinkedHashMap<String, Long>()
    val skipped = ArrayList<String>()

    init {
        layout.configFile.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }?.let { files += SnapshotFile(InstanceLayout.CONFIG_ENTRY, it) }

        tree(layout.pluginsDir, InstanceLayout.PLUGINS) { false }
        tree(layout.themesDir, InstanceLayout.THEMES, InstanceLayout::themeExcluded)
        tree(layout.uploadsDir, InstanceLayout.FILE_UPLOADS, InstanceLayout::uploadsExcluded)
        tree(layout.maintenanceDir, InstanceLayout.MAINTENANCE) { false }

        files.sortBy { it.path }
    }

    /** Bytes of all files now (a progress estimate; files may change before they are read). */
    fun totalBytes(): Long = files.sumOf { it.file.length() }

    private fun tree(root: File, prefix: String, exclude: (String) -> Boolean) {
        val rootPath = root.toPath()

        if (Files.isSymbolicLink(rootPath)) {
            skipped.add(prefix)

            return
        }

        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return
        }

        walk(rootPath, "", prefix, exclude)
    }

    private fun walk(directory: Path, relative: String, prefix: String, exclude: (String) -> Boolean) {
        val children = Files.list(directory).use { stream -> stream.toArray().map { it as Path } }.sortedBy { it.fileName.toString() }

        children.forEach { child ->
            val childRelative = if (relative.isEmpty()) child.fileName.toString() else "$relative/${child.fileName}"

            if (exclude(childRelative)) {
                return@forEach
            }

            val name = ArchivePaths.validate("$prefix/$childRelative")

            when {
                Files.isSymbolicLink(child) -> skipped.add(name)
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> {
                    directories[name] = runCatching { Files.getLastModifiedTime(child).toMillis() }.getOrDefault(0L)
                    walk(child, childRelative, prefix, exclude)
                }

                Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) -> files += SnapshotFile(name, child.toFile())
                else -> skipped.add(name)
            }
        }
    }
}
