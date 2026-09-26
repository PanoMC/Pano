package com.panomc.platform.archive

import com.panomc.platform.archive.PanoArcException.Code
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ArchiveRoundTripTest {
    @TempDir
    lateinit var temp: File

    private val pano = ArchivePanoInfo("1.0.0-alpha.517", "pano_", mapOf("core" to 55, "pano-plugin-market" to 3))
    private val workloadKey = ByteArray(32) { (it * 7).toByte() }

    private fun fails(code: Code, block: () -> Unit) {
        val error = assertThrows<PanoArcException> { block() }

        assertEquals(code, error.code, error.message)
    }

    private fun sha(bytes: ByteArray) = ArchiveManifest.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** An instance-like tree: config, a plugin jar, uploads with excluded cache, an empty file. */
    private fun sampleTree(): File {
        val app = File(temp, "instance").apply { mkdirs() }

        File(app, "config.conf").writeText("jwt-key = \"abc\"\n")
        File(app, "plugins").mkdirs()
        File(app, "plugins/market.jar").writeBytes(Random(1).nextBytes(300_000))
        File(app, "plugins/disabled.txt").writeText("")
        File(app, "file-uploads/posts").mkdirs()
        File(app, "file-uploads/posts/a.png").writeBytes(Random(2).nextBytes(2_500_000))
        File(app, "file-uploads/cache").mkdirs()
        File(app, "file-uploads/cache/tmp.bin").writeBytes(ByteArray(10))

        return app
    }

    private fun writeArchive(app: File, encryption: PanoArcEncryption?, dump: ByteArray = "dump".toByteArray()): Pair<ByteArray, ArchiveManifest> {
        val out = ByteArrayOutputStream()

        val manifest = PanoArchive.writer(out, encryption).use { writer ->
            writer.addBytes(ArchiveManifest.DB_DUMP_ENTRY, dump)
            writer.addTree(app, "app") { it == "file-uploads/cache" }
            writer.finish(
                ArchiveManifest.KIND_PANO_INSTANCE, "pano/1.0.0-alpha.517",
                ArchiveSource(instanceName = "Test"), pano, createdAt = 1790000000000
            )
        }

        return out.toByteArray() to manifest
    }

    private fun assertSameTree(expected: File, actual: File, excluded: Set<String> = emptySet()) {
        expected.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(expected).invariantSeparatorsPath

            if (excluded.any { relative.startsWith(it) }) {
                assertFalse(File(actual, relative).exists(), "$relative should be excluded")
            } else {
                assertArrayEquals(file.readBytes(), File(actual, relative).readBytes(), relative)
            }
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()

        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        return out.toByteArray()
    }

    private fun entriesOf(zip: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()

        ZipInputStream(zip.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break

                result.add(entry.name to input.readBytes())
            }
        }

        return result
    }

    @Test
    fun `plain archive round trip writes manifest last and verifies`() {
        val app = sampleTree()
        val (bytes, manifest) = writeArchive(app, null)

        assertEquals("manifest.json", entriesOf(bytes).last().first)
        assertEquals(listOf("db/dump.sql.gz", "app/config.conf", "app/file-uploads/posts/a.png",
            "app/plugins/disabled.txt", "app/plugins/market.jar"), manifest.entries.map { it.path })
        assertEquals(ArchiveDbInfo("mariadb", "pano-native", 4, sha("dump".toByteArray())), manifest.db)
        assertEquals(4, manifest.files.count)
        assertEquals(sha(File(app, "plugins/market.jar").readBytes()), manifest.entries.last().sha256)

        val target = File(temp, "staging")
        val read = PanoArchive.extract(bytes.inputStream(), PanoArcKeys.NONE, target)

        assertEquals(manifest, read)
        assertEquals(pano, read.pano)
        assertEquals(1790000000000, read.createdAt)
        assertSameTree(app, File(target, "app"), setOf("file-uploads/cache"))
        assertArrayEquals("dump".toByteArray(), File(target, "db/dump.sql.gz").readBytes())
        assertFalse(File(target, "manifest.json").exists())
    }

    @Test
    fun `workload and passphrase archives round trip`() {
        val app = sampleTree()

        val (workloadBytes, workloadManifest) = writeArchive(app, PanoArcEncryption.Workload(workloadKey, "1"))
        val workloadTarget = File(temp, "w")

        assertEquals(workloadManifest, PanoArchive.extract(workloadBytes.inputStream(), PanoArcKeys(workloadKey = { workloadKey }), workloadTarget))
        assertSameTree(app, File(workloadTarget, "app"), setOf("file-uploads/cache"))

        val (passBytes, passManifest) = writeArchive(app, PanoArcEncryption.Passphrase("pw".toCharArray(), t = 1, mKiB = 8192))

        assertEquals(passManifest, PanoArchive.verify(passBytes.inputStream(), PanoArcKeys(passphrase = "pw".toCharArray())))
        fails(Code.WRONG_PASSPHRASE) { PanoArchive.verify(passBytes.inputStream(), PanoArcKeys(passphrase = "nope".toCharArray())) }
    }

    @Test
    fun `empty archive round trips`() {
        val out = ByteArrayOutputStream()
        val manifest = PanoArchive.writer(out, PanoArcEncryption.Workload(workloadKey, "1")).use {
            it.finish(ArchiveManifest.KIND_PANO_INSTANCE, "pano/test")
        }

        val read = PanoArchive.verify(out.toByteArray().inputStream(), PanoArcKeys(workloadKey = { workloadKey }))

        assertEquals(manifest, read)
        assertEquals(0, read.entries.size)
        assertEquals(null, read.db)
    }

    @Test
    fun `tampered envelope inside an archive is detected by the reader`() {
        val (bytes, _) = writeArchive(sampleTree(), PanoArcEncryption.Workload(workloadKey, "1"))
        val keys = PanoArcKeys(workloadKey = { workloadKey })

        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 4).toByte() }

        fails(Code.TAMPERED) { PanoArchive.verify(flipped.inputStream(), keys) }

        // Cut after the zip's local entries but before the envelope's final chunk: must not pass.
        fails(Code.TRUNCATED) { PanoArchive.verify(bytes.copyOf(bytes.size - 10).inputStream(), keys) }
    }

    @Test
    fun `symlinks are skipped and listed, never followed`() {
        val app = sampleTree()
        val outside = File(temp, "outside").apply { mkdirs() }

        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(app, "link.txt").toPath(), File(outside, "secret.txt").toPath())
        Files.createSymbolicLink(File(app, "plugins/linkdir").toPath(), outside.toPath())

        val (bytes, manifest) = writeArchive(app, null)

        assertEquals(listOf("app/link.txt", "app/plugins/linkdir"), manifest.skipped)
        assertTrue(manifest.entries.none { it.path.contains("link") })

        val target = File(temp, "staging")
        val read = PanoArchive.extract(bytes.inputStream(), PanoArcKeys.NONE, target)

        assertEquals(manifest.skipped, read.skipped)
        assertFalse(File(target, "app/link.txt").exists())
        assertFalse(File(target, "app/plugins/linkdir").exists())
        assertTrue(target.walkTopDown().none { it.readTextOrNull() == "secret" })
    }

    private fun File.readTextOrNull() = if (isFile) readText() else null

    @Test
    fun `symlinked tree root is skipped`() {
        val real = sampleTree()
        val link = File(temp, "link-root")

        Files.createSymbolicLink(link.toPath(), real.toPath())

        val out = ByteArrayOutputStream()
        val manifest = ArchiveWriter(out).use { writer ->
            writer.addTree(link, "app")
            writer.finish(ArchiveManifest.KIND_PANO_INSTANCE, "pano/test")
        }

        assertEquals(listOf("app"), manifest.skipped)
        assertEquals(0, manifest.entries.size)
    }

    @Test
    fun `writer refuses unsafe names, duplicates and the reserved manifest`() {
        ArchiveWriter(ByteArrayOutputStream()).use { writer ->
            listOf("../x", "/etc/passwd", "a/../../b", "a\\b", "C:/x", "a//b", "./a", "a/", "").forEach { name ->
                fails(Code.UNSAFE_ENTRY) { writer.addBytes(name, ByteArray(1)) }
            }

            fails(Code.UNSAFE_ENTRY) { writer.addBytes("manifest.json", ByteArray(1)) }

            writer.addBytes("app/a", ByteArray(1))
            fails(Code.INVALID_ARCHIVE) { writer.addBytes("app/a", ByteArray(1)) }
        }
    }

    @Test
    fun `writer enforces its limits`() {
        ArchiveWriter(ByteArrayOutputStream(), ArchiveLimits(maxEntries = 2)).use { writer ->
            writer.addBytes("a", ByteArray(1))
            writer.addBytes("b", ByteArray(1))
            fails(Code.TOO_MANY_ENTRIES) { writer.addBytes("c", ByteArray(1)) }
        }

        ArchiveWriter(ByteArrayOutputStream(), ArchiveLimits(maxTotalBytes = 1000)).use { writer ->
            fails(Code.TOO_LARGE) { writer.addBytes("a", ByteArray(1001)) }
        }
    }

    @Test
    fun `zip slip entries are refused and nothing escapes`() {
        val manifest = "{}".toByteArray()

        listOf("../evil.txt", "app/../../evil.txt", "/tmp/evil.txt", "app\\..\\..\\evil.txt").forEach { name ->
            val target = File(temp, "staging-${name.hashCode()}")

            fails(Code.UNSAFE_ENTRY) {
                ArchiveReader().extract(zipOf(name to "x".toByteArray(), "manifest.json" to manifest).inputStream(), target)
            }
        }

        assertFalse(File(temp, "evil.txt").exists())
        assertFalse(File("/tmp/evil.txt").exists())
    }

    @Test
    fun `zip bomb stops at the size limit`() {
        val bomb = zipOf("app/zeros" to ByteArray(20 * 1024 * 1024), "manifest.json" to "{}".toByteArray())

        assertTrue(bomb.size < 100_000)
        fails(Code.TOO_LARGE) { ArchiveReader(ArchiveLimits(maxTotalBytes = 1024 * 1024)).verify(bomb.inputStream()) }
        fails(Code.TOO_LARGE) { ArchiveReader(ArchiveLimits(maxEntryBytes = 1024 * 1024)).extract(bomb.inputStream(), File(temp, "s")) }
    }

    @Test
    fun `too many entries stop the reader`() {
        val zip = zipOf(*(1..10).map { "app/f$it" to ByteArray(1) }.toTypedArray())

        fails(Code.TOO_MANY_ENTRIES) { ArchiveReader(ArchiveLimits(maxEntries = 5)).verify(zip.inputStream()) }
    }

    @Test
    fun `hash mismatch, missing and unlisted entries are refused`() {
        val (bytes, _) = writeArchive(sampleTree(), null)
        val entries = entriesOf(bytes)

        val modified = entries.map { (name, data) -> if (name == "app/config.conf") name to "jwt-key = \"evil\"\n".toByteArray() else name to data }
        fails(Code.HASH_MISMATCH) { ArchiveReader().verify(zipOf(*modified.toTypedArray()).inputStream()) }

        val sameSize = entries.map { (name, data) ->
            if (name == "app/plugins/market.jar") name to data.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else name to data
        }
        fails(Code.HASH_MISMATCH) { ArchiveReader().verify(zipOf(*sameSize.toTypedArray()).inputStream()) }

        val missing = entries.filterNot { it.first == "app/plugins/disabled.txt" }
        fails(Code.HASH_MISMATCH) { ArchiveReader().verify(zipOf(*missing.toTypedArray()).inputStream()) }

        val extra = entries.dropLast(1) + ("app/extra.txt" to ByteArray(3)) + entries.last()
        fails(Code.HASH_MISMATCH) { ArchiveReader().verify(zipOf(*extra.toTypedArray()).inputStream()) }
    }

    @Test
    fun `manifest summaries are verified`() {
        val (bytes, manifest) = writeArchive(sampleTree(), null)
        val entries = entriesOf(bytes).dropLast(1)

        val badFiles = manifest.copy(files = manifest.files.copy(count = 99))
        fails(Code.HASH_MISMATCH) { ArchiveReader().verify(zipOf(*(entries + ("manifest.json" to badFiles.encode())).toTypedArray()).inputStream()) }

        val badDb = manifest.copy(db = manifest.db!!.copy(sha256 = sha(ByteArray(1))))
        fails(Code.HASH_MISMATCH) { ArchiveReader().verify(zipOf(*(entries + ("manifest.json" to badDb.encode())).toTypedArray()).inputStream()) }

        val newer = String(manifest.encode()).replace("\"formatVersion\" : 1", "\"formatVersion\" : 2").toByteArray()
        fails(Code.UNSUPPORTED_FORMAT) { ArchiveReader().verify(zipOf(*(entries + ("manifest.json" to newer)).toTypedArray()).inputStream()) }
    }

    @Test
    fun `manifest must exist and be last`() {
        val (bytes, _) = writeArchive(sampleTree(), null)
        val entries = entriesOf(bytes)

        fails(Code.INVALID_ARCHIVE) { ArchiveReader().verify(zipOf(*entries.dropLast(1).toTypedArray()).inputStream()) }

        val manifestFirst = listOf(entries.last()) + entries.dropLast(1)
        fails(Code.INVALID_ARCHIVE) { ArchiveReader().verify(zipOf(*manifestFirst.toTypedArray()).inputStream()) }
    }
}
