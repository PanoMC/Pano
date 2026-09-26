package com.panomc.platform.archive.instance

import com.panomc.platform.archive.ArchiveLimits
import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchivePanoInfo
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArchive
import com.panomc.platform.archive.db.PanoNativeDumper
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * Writes a `pano-instance` archive of a self-hosted Pano: `db/dump.sql.gz` from [PanoNativeDumper]
 * (consistent snapshot, prefix tables only), `app/config.conf` as is (secrets kept, archive-format.md
 * section 2), then `app/plugins`, `app/themes` (minus license tokens), `app/file-uploads` (minus
 * cache/temp/transfer) and `app/maintenance`, symlinks skipped, `manifest.json` last with the core
 * and plugin scheme versions read inside the dump's snapshot.
 */
class InstanceArchiver(
    private val layout: InstanceLayout,
    private val dbPrefix: String,
    private val panoVersion: String,
    private val source: ArchiveSource = ArchiveSource(),
    private val producer: String = "pano/$panoVersion",
    /** The config bytes to archive; defaults to the config file on disk. */
    private val configBytes: () -> ByteArray? = { layout.configFile.takeIf { it.isFile }?.readBytes() }
) {
    /**
     * Streams the archive into [output] ([encryption] null = plain zip) and closes it. [connection]
     * is used for the whole dump (one session); null archives files only (tests, `kind` checks).
     */
    suspend fun archive(
        output: OutputStream,
        encryption: PanoArcEncryption?,
        connection: SqlConnection?,
        limits: ArchiveLimits = ArchiveLimits(),
        schemeVersions: Map<String, Int> = emptyMap()
    ): ArchiveManifest {
        val dumpFile = connection?.let { File(layout.tempDir, "dump-${UUID.randomUUID()}.sql.gz") }

        try {
            var versions = schemeVersions

            if (connection != null && dumpFile != null) {
                withContext(Dispatchers.IO) { layout.tempDir.mkdirs() }

                val stream = withContext(Dispatchers.IO) { dumpFile.outputStream().buffered(64 * 1024) }

                try {
                    versions = PanoNativeDumper(dbPrefix).dumpGzip(connection, stream).schemeVersions + schemeVersions
                } finally {
                    withContext(Dispatchers.IO) { stream.close() }
                }
            }

            return withContext(Dispatchers.IO) {
                PanoArchive.writer(output, encryption, limits).use { writer ->
                    dumpFile?.let { writer.addFile(ArchiveManifest.DB_DUMP_ENTRY, it) }
                    configBytes()?.let { writer.addBytes(InstanceLayout.CONFIG_ENTRY, it) }

                    writer.addTree(layout.pluginsDir, InstanceLayout.PLUGINS)
                    writer.addTree(layout.themesDir, InstanceLayout.THEMES, InstanceLayout::themeExcluded)
                    writer.addTree(layout.uploadsDir, InstanceLayout.FILE_UPLOADS, InstanceLayout::uploadsExcluded)
                    writer.addTree(layout.maintenanceDir, InstanceLayout.MAINTENANCE)

                    writer.finish(
                        kind = ArchiveManifest.KIND_PANO_INSTANCE,
                        producer = producer,
                        source = source,
                        pano = ArchivePanoInfo(panoVersion, dbPrefix, versions),
                        dbDumpTool = PanoNativeDumper.DUMP_TOOL
                    )
                }
            }
        } finally {
            dumpFile?.delete()
        }
    }
}
