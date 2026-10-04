package com.panomc.platform.archive.instance

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcException.Code
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.archive.db.SanitisedSqlImporter
import com.panomc.platform.archive.db.SqlLiterals
import com.panomc.platform.config.HoconWriter
import com.panomc.platform.config.PanoConfig
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Restores a `pano-instance` archive onto this machine (archive-format.md section 5, self-host order):
 *
 * 1. [stage]: decrypt + extract into `<tempDir>/restore-<id>` with every hash and the manifest
 *    verified, reject other kinds and `ARCHIVE_NEWER_THAN_TARGET` (scheme or config versions this Pano
 *    does not know), rewrite the archived config for this target, and dry-run the whole dump through
 *    [SanitisedSqlImporter] so a rejected dump never gets as far as dropping anything.
 * 2. [apply] (Pano in maintenance mode, caller's job): drop the target's and the archive's prefix
 *    tables, import the dump, swap the contents of plugins/themes/file-uploads/maintenance (keeping
 *    the target's uploads cache/temp/transfer and theme license tokens), then hand the rewritten config
 *    to [apply]'s config sink. A running Pano must take the config through its ConfigManager (it
 *    rewrites config.conf on shutdown); the default sink writes the file for a stopped Pano.
 * 3. [discard] the staging directory (also holds the replaced files until then).
 *
 * The staging directory lives in [InstanceLayout.tempDir] so the swap is a same-filesystem move.
 */
class InstanceRestorer(
    private val layout: InstanceLayout,
    /** The target's current config as JSON (DB credentials, server, uploads folder, prefix). */
    private val targetConfig: JsonObject,
    /** Scheme versions this Pano knows (`core` = latest migration `to`); archive values above → refused. */
    private val targetSchemeVersions: Map<String, Int>,
    /** The latest config version this Pano migrates to; null = not checked. */
    private val targetConfigVersion: Int? = null,
    private val hostedEmail: JsonObject? = null,
    private val limits: ArchiveLimits = ArchiveLimits()
) {
    class Staged internal constructor(
        val directory: File,
        val manifest: ArchiveManifest,
        /** The archived config rewritten for this target. */
        val config: JsonObject,
        val dump: SanitisedSqlImporter.Summary
    ) {
        internal val root get() = File(directory, "archive")
        internal val backup get() = File(directory, "replaced")
        val archivePrefix: String get() = manifest.pano!!.dbPrefix
    }

    data class Result(val manifest: ArchiveManifest, val droppedTables: List<String>, val dump: SanitisedSqlImporter.Summary)

    private val targetPrefix: String = targetConfig.getJsonObject("database")?.getString("prefix") ?: ""

    /** Blocking: stage, verify and check [input] (plain or enveloped; [keys] open it). */
    fun stage(input: InputStream, keys: PanoArcKeys): Staged {
        val directory = File(layout.tempDir, "restore-${UUID.randomUUID()}")

        try {
            val root = File(directory, "archive")
            val manifest = PanoArchive.extract(input, keys, root, limits)

            if (manifest.kind != ArchiveManifest.KIND_PANO_INSTANCE) {
                throw PanoArcException(Code.WRONG_KIND, "The archive is a ${manifest.kind}, not a Pano instance.")
            }

            val pano = manifest.pano ?: throw PanoArcException(Code.INVALID_ARCHIVE, "The manifest has no pano section.")

            if (!SanitisedSqlImporter.PREFIX.matches(pano.dbPrefix)) {
                throw PanoArcException(Code.INVALID_ARCHIVE, "Invalid database prefix in the manifest.")
            }

            pano.schemeVersions.forEach { (owner, version) ->
                val known = targetSchemeVersions[owner]

                if (known != null && version > known) {
                    throw PanoArcException(
                        Code.ARCHIVE_NEWER_THAN_TARGET,
                        "The archive's $owner scheme version $version is newer than this Pano's $known; update Pano to ${pano.version} or later first."
                    )
                }
            }

            val dumpFile = File(root, ArchiveManifest.DB_DUMP_ENTRY)
            val configFile = File(root, InstanceLayout.CONFIG_ENTRY)

            if (manifest.db == null || !dumpFile.isFile) {
                throw PanoArcException(Code.INVALID_ARCHIVE, "The archive has no database dump.")
            }

            if (!configFile.isFile) {
                throw PanoArcException(Code.INVALID_ARCHIVE, "The archive has no config.conf.")
            }

            val archivedConfig = ConfigRewriter.parseHocon(configFile.readText())
            val configVersion = archivedConfig.getInteger("config-version")

            if (targetConfigVersion != null && configVersion != null && configVersion > targetConfigVersion) {
                throw PanoArcException(
                    Code.ARCHIVE_NEWER_THAN_TARGET,
                    "The archive's config version $configVersion is newer than this Pano's $targetConfigVersion."
                )
            }

            val archivedPrefix = archivedConfig.getJsonObject("database")?.getString("prefix")

            if (archivedPrefix != null && archivedPrefix != pano.dbPrefix) {
                throw PanoArcException(Code.INVALID_ARCHIVE, "The archived config's prefix does not match the manifest.")
            }

            val summary = dumpFile.inputStream().use { SanitisedSqlImporter(pano.dbPrefix).validateGzip(it) }
            val config = ConfigRewriter.rewrite(archivedConfig, targetConfig, hostedEmail)

            return Staged(directory, manifest, config, summary)
        } catch (e: Throwable) {
            deleteTree(directory)

            throw e
        }
    }

    /** Drops, imports, swaps the files and sinks the config; [connection] is one session for all of it. */
    suspend fun apply(
        staged: Staged,
        connection: SqlConnection,
        configSink: suspend (JsonObject) -> Unit = { config -> withContext(Dispatchers.IO) { writeConfigFile(config) } }
    ): Result {
        val dropped = dropPrefixTables(connection, setOf(targetPrefix, staged.archivePrefix))
        val dump = withContext(Dispatchers.IO) { File(staged.root, ArchiveManifest.DB_DUMP_ENTRY).inputStream().buffered() }

        val summary = try {
            SanitisedSqlImporter(staged.archivePrefix).importGzip(dump, connection)
        } finally {
            withContext(Dispatchers.IO) { dump.close() }
        }

        withContext(Dispatchers.IO) { replaceFiles(staged) }

        configSink(staged.config)

        return Result(staged.manifest, dropped, summary)
    }

    fun discard(staged: Staged) = deleteTree(staged.directory)

    /** [stage] + [apply] + [discard] in one go. */
    suspend fun restore(
        input: InputStream,
        keys: PanoArcKeys,
        connection: SqlConnection,
        configSink: suspend (JsonObject) -> Unit = { config -> withContext(Dispatchers.IO) { writeConfigFile(config) } }
    ): Result {
        val staged = withContext(Dispatchers.IO) { stage(input, keys) }

        try {
            return apply(staged, connection, configSink)
        } finally {
            withContext(Dispatchers.IO) { discard(staged) }
        }
    }

    fun writeConfigFile(config: JsonObject) {
        layout.configFile.absoluteFile.parentFile?.mkdirs()
        layout.configFile.writeText(HoconWriter.render(config, PanoConfig::class.java))
    }

    private suspend fun dropPrefixTables(connection: SqlConnection, prefixes: Set<String>): List<String> {
        val tables = connection
            .query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME")
            .execute().coAwait()
            .map { it.getString(0) }
            .filter { name -> prefixes.any { name.startsWith(it) } }

        if (tables.isNotEmpty()) {
            connection.query("SET SESSION foreign_key_checks = 0").execute().coAwait()
            connection.query("DROP TABLE IF EXISTS ${tables.joinToString(", ") { SqlLiterals.identifier(it) }}").execute().coAwait()
        }

        return tables
    }

    private fun replaceFiles(staged: Staged) {
        val backup = staged.backup

        layout.directories.forEach { (prefix, target) ->
            val stagedDir = File(staged.root, prefix)
            val oldDir = File(backup, prefix.substringAfter('/'))

            Files.createDirectories(oldDir.toPath())
            Files.createDirectories(target.toPath())

            // Contents, not the directory itself: the target may be a mount point or a symlink.
            children(target).forEach { moveOrCopy(it, File(oldDir, it.fileName.toString()).toPath()) }

            if (stagedDir.isDirectory) {
                children(stagedDir).forEach { moveOrCopy(it, File(target, it.fileName.toString()).toPath()) }
            }

            when (prefix) {
                InstanceLayout.FILE_UPLOADS -> InstanceLayout.UPLOADS_EXCLUDED_DIRS.forEach { name ->
                    val old = File(oldDir, name)

                    if (Files.exists(old.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        deleteTree(File(target, name))
                        moveOrCopy(old.toPath(), File(target, name).toPath())
                    }
                }

                InstanceLayout.THEMES -> children(oldDir).filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { theme ->
                    val newTheme = File(target, theme.fileName.toString())

                    InstanceLayout.THEME_EXCLUDED_FILES.forEach { name ->
                        val token = theme.resolve(name)

                        if (newTheme.isDirectory && Files.isRegularFile(token, LinkOption.NOFOLLOW_LINKS) && !File(newTheme, name).exists()) {
                            Files.copy(token, File(newTheme, name).toPath())
                        }
                    }
                }
            }
        }
    }

    private fun children(directory: File): List<Path> =
        if (!Files.isDirectory(directory.toPath())) emptyList()
        else Files.list(directory.toPath()).use { stream -> stream.toArray().map { it as Path } }

    private fun moveOrCopy(source: Path, target: Path) {
        try {
            Files.move(source, target)
        } catch (e: IOException) {
            if (e is java.nio.file.FileAlreadyExistsException) {
                throw e
            }

            copyTree(source, target)
            deleteTree(source.toFile())
        }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        /** Deletes [file] and everything below it without following symlinks. */
        fun deleteTree(file: File) {
            val path = file.toPath()

            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return
            }

            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                Files.delete(path)
            }
        }
    }
}
