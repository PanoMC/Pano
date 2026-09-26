package com.panomc.platform.archive.instance

import com.panomc.platform.archive.ArchiveManifest
import com.panomc.platform.archive.ArchivePanoInfo
import com.panomc.platform.archive.ArchiveSource
import com.panomc.platform.archive.PanoArcEncryption
import com.panomc.platform.archive.PanoArcException
import com.panomc.platform.archive.PanoArcException.Code
import com.panomc.platform.archive.PanoArcKeys
import com.panomc.platform.archive.PanoArchive
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class InstanceArchiverTest {
    @TempDir
    lateinit var temp: File

    private fun layout(root: File) = InstanceLayout(
        configFile = File(root, "config.conf"),
        pluginsDir = File(root, "plugins"),
        themesDir = File(root, "themes"),
        uploadsDir = File(root, "file-uploads"),
        maintenanceDir = File(root, "maintenance"),
        tempDir = File(root, ".temp")
    )

    private fun sampleInstance(root: File): InstanceLayout {
        val layout = layout(root)

        layout.configFile.apply { parentFile.mkdirs() }.writeText(CONFIG)
        File(root, "plugins/market").mkdirs()
        File(root, "plugins/market.jar").writeBytes(ByteArray(1000) { it.toByte() })
        File(root, "plugins/disabled.txt").writeText("pano-plugin-x\n")
        File(root, "plugins/market/config.conf").writeText("a = 1\n")
        File(root, "themes/vanilla-theme/build").mkdirs()
        File(root, "themes/vanilla-theme/build/index.js").writeText("console.log(1)")
        File(root, "themes/vanilla-theme/.pano-license.jwt").writeText("secret.jwt.token")
        File(root, "themes/vanilla-theme/.pano-license.jwt.tmp").writeText("x")
        File(root, "file-uploads/post/thumbnail").mkdirs()
        File(root, "file-uploads/post/thumbnail/a.png").writeBytes(ByteArray(5000) { 7 })
        File(root, "file-uploads/cache").mkdirs()
        File(root, "file-uploads/cache/c.bin").writeText("cache")
        File(root, "file-uploads/temp").mkdirs()
        File(root, "file-uploads/temp/upload.part").writeText("temp")
        File(root, "file-uploads/transfer").mkdirs()
        File(root, "file-uploads/transfer/spool").writeText("spool")
        File(root, "file-uploads/nested/cache").mkdirs()
        File(root, "file-uploads/nested/cache/kept.txt").writeText("only top-level cache is excluded")
        File(root, "maintenance").mkdirs()
        File(root, "maintenance/index.html").writeText("<h1>down</h1>")
        File(root, "logs").mkdirs()
        File(root, "logs/latest.log").writeText("never archived")
        File(root, ".temp").mkdirs()

        Files.createSymbolicLink(File(root, "file-uploads/escape").toPath(), File("/etc").toPath())
        Files.createSymbolicLink(File(root, "plugins/linked.jar").toPath(), File(root, "plugins/market.jar").toPath())

        return layout
    }

    private fun archive(layout: InstanceLayout, encryption: PanoArcEncryption? = null): Pair<ByteArray, ArchiveManifest> {
        val out = ByteArrayOutputStream()
        val manifest = runBlocking {
            InstanceArchiver(layout, "pano_", "1.0.0-alpha.520", ArchiveSource(instanceName = "Test"))
                .archive(out, encryption, null, schemeVersions = mapOf("core" to 55))
        }

        return out.toByteArray() to manifest
    }

    @Test
    fun `collector archives app dirs with the exclusions and skips symlinks`() {
        val layout = sampleInstance(File(temp, "instance"))
        val (bytes, manifest) = archive(layout, PanoArcEncryption.Passphrase("pw".toCharArray(), t = 1, mKiB = 8192))

        val paths = manifest.entries.map { it.path }

        assertEquals(
            listOf(
                "app/config.conf",
                "app/plugins/disabled.txt",
                "app/plugins/market/config.conf",
                "app/plugins/market.jar",
                "app/themes/vanilla-theme/build/index.js",
                "app/file-uploads/nested/cache/kept.txt",
                "app/file-uploads/post/thumbnail/a.png",
                "app/maintenance/index.html"
            ),
            paths
        )
        assertEquals(listOf("app/plugins/linked.jar", "app/file-uploads/escape").sorted(), manifest.skipped.sorted())
        assertEquals("pano/1.0.0-alpha.520", manifest.producer)
        assertEquals(ArchivePanoInfo("1.0.0-alpha.520", "pano_", mapOf("core" to 55)), manifest.pano)
        assertNull(manifest.db)
        assertTrue(File(layout.tempDir.path).listFiles()!!.isEmpty())

        val verified = PanoArchive.verify(bytes.inputStream(), PanoArcKeys(passphrase = "pw".toCharArray()))

        assertEquals(manifest.entries, verified.entries)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    /** A hand-built instance archive, so stage() can be checked without a database. */
    private fun handmade(
        dump: String = "CREATE TABLE `pano_x` (`id` int) ENGINE=InnoDB;\nINSERT INTO `pano_x` VALUES (1);\n",
        kind: String = ArchiveManifest.KIND_PANO_INSTANCE,
        core: Int = 55,
        config: String = CONFIG
    ): ByteArray {
        val out = ByteArrayOutputStream()

        PanoArchive.writer(out, null).use { writer ->
            writer.addBytes(ArchiveManifest.DB_DUMP_ENTRY, gzip(dump))
            writer.addBytes(InstanceLayout.CONFIG_ENTRY, config.toByteArray())
            writer.addBytes("app/plugins/market.jar", ByteArray(10))
            writer.finish(kind, "pano/test", pano = ArchivePanoInfo("1.0.0-alpha.520", "pano_", mapOf("core" to core, "market" to 3)))
        }

        return out.toByteArray()
    }

    private val target = JsonObject()
        .put("config-version", 30)
        .put("database", JsonObject().put("host", "db.target:3307").put("name", "target_db").put("username", "tu").put("password", "tp").put("prefix", "other_"))
        .put("server", JsonObject().put("http-port", 18080).put("ssl-mode", "DISABLED"))
        .put("file-uploads-folder", "/srv/uploads")

    private fun restorer(root: File, core: Int = 55, configVersion: Int? = 30) =
        InstanceRestorer(layout(root), target, mapOf("core" to core), configVersion)

    @Test
    fun `stage verifies, dry-runs the dump and rewrites the config for the target`() {
        val root = File(temp, "target")
        val staged = restorer(root).stage(handmade().inputStream(), PanoArcKeys.NONE)

        assertEquals(setOf("pano_x"), staged.dump.tables)
        assertEquals(1, staged.dump.rows)
        assertEquals("pano_", staged.archivePrefix)

        val config = staged.config

        assertEquals("keep-this-jwt-key", config.getString("jwt-key"))
        assertEquals("Archived Site", config.getString("website-name"))
        assertEquals("pano_", config.getJsonObject("database").getString("prefix"))
        assertEquals("db.target:3307", config.getJsonObject("database").getString("host"))
        assertEquals("target_db", config.getJsonObject("database").getString("name"))
        assertEquals("tu", config.getJsonObject("database").getString("username"))
        assertEquals("tp", config.getJsonObject("database").getString("password"))
        assertEquals(18080, config.getJsonObject("server").getInteger("http-port"))
        assertEquals("/srv/uploads", config.getString("file-uploads-folder"))
        assertEquals("smtp.archived", config.getJsonObject("email").getString("hostname"))

        val hosted = InstanceRestorer(layout(root), target, mapOf("core" to 55), hostedEmail = JsonObject().put("hostname", "relay"))
            .stage(handmade().inputStream(), PanoArcKeys.NONE)

        assertEquals("relay", hosted.config.getJsonObject("email").getString("hostname"))

        restorer(root).discard(staged)
        restorer(root).discard(hosted)

        assertFalse(File(root, ".temp").listFiles()!!.any())
    }

    private fun stageFails(code: Code, archive: ByteArray, root: File = File(temp, "target"), core: Int = 55, configVersion: Int? = 30) {
        val error = assertThrows<PanoArcException> { restorer(root, core, configVersion).stage(archive.inputStream(), PanoArcKeys.NONE) }

        assertEquals(code, error.code, error.message)
        assertFalse(File(root, ".temp").listFiles()?.any() ?: false, "staging must be cleaned up")
    }

    @Test
    fun `stage refuses newer archives, other kinds and unsafe dumps before touching anything`() {
        stageFails(Code.ARCHIVE_NEWER_THAN_TARGET, handmade(core = 56))
        stageFails(Code.ARCHIVE_NEWER_THAN_TARGET, handmade(), configVersion = 29)
        stageFails(Code.WRONG_KIND, handmade(kind = ArchiveManifest.KIND_MC_SERVER))
        stageFails(Code.UNSAFE_SQL, handmade(dump = "INSERT INTO `pano_x` VALUES (1);\nGRANT ALL ON *.* TO 'x'@'%';\n"))
        stageFails(Code.UNSAFE_SQL, handmade(dump = "DROP TABLE IF EXISTS `other_x`;\n"))
        stageFails(Code.INVALID_ARCHIVE, handmade(config = CONFIG.replace("prefix = \"pano_\"", "prefix = \"evil_\"")))
        stageFails(Code.INVALID_ARCHIVE, handmade(config = "this is { not hocon"))

        // A newer target is fine; unknown plugin ids are not compared.
        restorer(File(temp, "t2"), core = 60).stage(handmade().inputStream(), PanoArcKeys.NONE)
    }

    companion object {
        val CONFIG = """
            config-version = 30
            website-name = "Archived Site"
            jwt-key = "keep-this-jwt-key"
            database {
              type = "mariadb"
              host = "old-host"
              name = "old_db"
              username = "old_user"
              password = "old_password"
              prefix = "pano_"
            }
            server {
              http-port = 80
            }
            email {
              hostname = "smtp.archived"
            }
            file-uploads-folder = "file-uploads"
        """.trimIndent()
    }
}
