package com.panomc.node.archive

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.CRC32
import kotlin.random.Random

class ArchiveExtractorTest {
    @TempDir
    lateinit var root: File

    /** Plenty of space: the guard never trips unless a test wants it to. */
    private fun roomy() = DiskGuard(root, reserveBytes = 1024, usableSpace = { Long.MAX_VALUE / 2 })

    private val big = Random(7).nextBytes(300 * 1024)

    private val tree = linkedMapOf(
        "a.txt" to "alpha".toByteArray(),
        "dir/b.txt" to "bravo".toByteArray(),
        "dir/deeper/big.bin" to big
    )

    // ---- fixtures ---------------------------------------------------------------------------

    private fun tar(entries: Map<String, ByteArray> = tree, extra: (TarArchiveOutputStream) -> Unit = {}): ByteArray {
        val bytes = ByteArrayOutputStream()

        TarArchiveOutputStream(bytes).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            out.putArchiveEntry(TarArchiveEntry("empty/"))
            out.closeArchiveEntry()

            entries.forEach { (name, data) ->
                val entry = TarArchiveEntry(name)
                entry.size = data.size.toLong()
                out.putArchiveEntry(entry)
                out.write(data)
                out.closeArchiveEntry()
            }

            extra(out)
        }

        return bytes.toByteArray()
    }

    private fun compress(data: ByteArray, open: (OutputStream) -> OutputStream): ByteArray {
        val bytes = ByteArrayOutputStream()

        open(bytes).use { it.write(data) }

        return bytes.toByteArray()
    }

    private fun zip(entries: Map<String, ByteArray> = tree, extra: (ZipArchiveOutputStream) -> Unit = {}): ByteArray {
        val bytes = ByteArrayOutputStream()

        ZipArchiveOutputStream(bytes).use { out ->
            out.putArchiveEntry(ZipArchiveEntry("empty/"))
            out.closeArchiveEntry()

            entries.forEach { (name, data) ->
                out.putArchiveEntry(ZipArchiveEntry(name))
                out.write(data)
                out.closeArchiveEntry()
            }

            extra(out)
        }

        return bytes.toByteArray()
    }

    /**
     * A zip whose entries are STORED *and* sized only by a trailing data descriptor, the shape a
     * streaming zipper produces and the one a stream reader has to be told to accept.
     */
    private fun storedWithDescriptors(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()

        fun OutputStream.int(value: Long) = (0 until 4).forEach { write(((value ushr (8 * it)) and 0xFF).toInt()) }
        fun OutputStream.short(value: Int) = (0 until 2).forEach { write((value ushr (8 * it)) and 0xFF) }

        entries.forEach { (name, data) ->
            val nameBytes = name.toByteArray()
            val crc = CRC32().apply { update(data) }.value
            val offset = out.size().toLong()

            out.int(0x04034b50); out.short(20); out.short(0x0008); out.short(0); out.short(0); out.short(0)
            out.int(0); out.int(0); out.int(0); out.short(nameBytes.size); out.short(0); out.write(nameBytes)
            out.write(data)
            out.int(0x08074b50); out.int(crc); out.int(data.size.toLong()); out.int(data.size.toLong())

            central.int(0x02014b50); central.short(20); central.short(20); central.short(0x0008); central.short(0)
            central.short(0); central.short(0); central.int(crc); central.int(data.size.toLong()); central.int(data.size.toLong())
            central.short(nameBytes.size); central.short(0); central.short(0); central.short(0); central.short(0)
            central.int(0); central.int(offset); central.write(nameBytes)
        }

        val centralOffset = out.size().toLong()
        out.write(central.toByteArray())

        out.int(0x06054b50); out.short(0); out.short(0); out.short(entries.size); out.short(entries.size)
        out.int(central.size().toLong()); out.int(centralOffset); out.short(0)

        return out.toByteArray()
    }

    private fun sevenZ(): ByteArray {
        val file = File(root, "fixture-${System.nanoTime()}.7z")

        SevenZOutputFile(file).use { out ->
            tree.forEach { (name, data) ->
                val entry = out.createArchiveEntry(File(root, "unused"), name)
                entry.size = data.size.toLong()
                entry.isDirectory = false
                out.putArchiveEntry(entry)
                out.write(data)
                out.closeArchiveEntry()
            }
        }

        return file.readBytes().also { file.delete() }
    }

    private fun fixtures(): Map<ArchiveFormat, ByteArray> {
        val tar = tar()

        return linkedMapOf(
            ArchiveFormat.ZIP to zip(),
            ArchiveFormat.TAR to tar,
            ArchiveFormat.TAR_GZ to compress(tar) { GzipCompressorOutputStream(it) },
            ArchiveFormat.TAR_BZ2 to compress(tar) { BZip2CompressorOutputStream(it) },
            ArchiveFormat.TAR_XZ to compress(tar) { XZCompressorOutputStream(it) },
            ArchiveFormat.SEVEN_Z to sevenZ()
        )
    }

    /** No mark/reset, and never more than a few hundred bytes per read, like a network body. */
    private fun liveStream(bytes: ByteArray, failAfter: Int = Int.MAX_VALUE): InputStream = object : InputStream() {
        private val source = ByteArrayInputStream(bytes)
        private var delivered = 0

        override fun read(): Int {
            val one = ByteArray(1)

            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (delivered >= failAfter) {
                throw IOException("connection reset")
            }

            val read = source.read(b, off, minOf(len, 777))

            if (read > 0) {
                delivered += read
            }

            return read
        }

        override fun markSupported() = false
    }

    private fun assertTree(target: File) {
        assertEquals("alpha", File(target, "a.txt").readText())
        assertEquals("bravo", File(target, "dir/b.txt").readText())
        assertArrayEquals(big, File(target, "dir/deeper/big.bin").readBytes())
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    // ---- extraction -------------------------------------------------------------------------

    @Test
    fun `extracts every format from a stream that cannot mark`() {
        fixtures().forEach { (format, bytes) ->
            val target = File(root, "stream-${format.id}")
            val temp = File(root, "temp-${format.id}")
            var announced: ArchiveFormat? = null

            val result = ArchiveExtractor.extractStream(liveStream(bytes), target, root, roomy(), temp, "upload") { announced = it }

            assertEquals(format, result, "format of ${format.id}")
            assertEquals(format, announced)
            assertTree(target)

            // A 7z goes through a temp file, which must be gone again.
            assertTrue(temp.listFiles().isNullOrEmpty(), "temp files left for ${format.id}")
        }
    }

    @Test
    fun `extracts every format from a file`() {
        fixtures().forEach { (format, bytes) ->
            val archive = File(root, "archive-${format.id}.bin").apply { writeBytes(bytes) }
            val target = File(root, "file-${format.id}")

            assertEquals(format, ArchiveExtractor.extractFile(archive, target, root, roomy()))
            assertTree(target)
        }
    }

    @Test
    fun `reads stored zip entries that are sized by a data descriptor`() {
        val bytes = storedWithDescriptors(linkedMapOf("one.txt" to "first".toByteArray(), "sub/two.bin" to big))
        val target = File(root, "dd")

        ArchiveExtractor.extractStream(liveStream(bytes), target, root, roomy(), root)

        assertEquals("first", File(target, "one.txt").readText())
        assertArrayEquals(big, File(target, "sub/two.bin").readBytes())
    }

    @Test
    fun `unpacks a single gz file under its own name`() {
        val bytes = compress(big) { GzipCompressorOutputStream(it) }
        val target = File(root, "gz")

        val format = ArchiveExtractor.extractStream(liveStream(bytes), target, root, roomy(), root, "world.dat.gz")

        assertEquals(ArchiveFormat.GZ, format)
        assertArrayEquals(big, File(target, "world.dat").readBytes())
        assertEquals("file", ArchiveExtractor.gzName("../.."))
        assertEquals("file", ArchiveExtractor.gzName(null))
        assertEquals("data.out", ArchiveExtractor.gzName("c:\\x\\data"))
    }

    @Test
    fun `refuses an entry that climbs out of the target, in zip and tar`() {
        val evil = mapOf("../../escaped.txt" to "owned".toByteArray())

        listOf(zip(evil), tar(evil)).forEachIndexed { index, bytes ->
            val target = File(root, "slip$index")

            assertThrows(IllegalArgumentException::class.java) {
                ArchiveExtractor.extractStream(liveStream(bytes), target, root, roomy(), root)
            }
        }

        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertFalse(File(root, "escaped.txt").exists())
    }

    @Test
    fun `never creates links from either format`() {
        val tarBytes = tar(mapOf("real.txt" to "x".toByteArray())) { out ->
            val link = TarArchiveEntry("link", TarConstants.LF_SYMLINK)
            link.linkName = "/etc/passwd"
            out.putArchiveEntry(link)
            out.closeArchiveEntry()

            val hard = TarArchiveEntry("hard", TarConstants.LF_LINK)
            hard.linkName = "real.txt"
            out.putArchiveEntry(hard)
            out.closeArchiveEntry()
        }

        val zipBytes = zip(mapOf("real.txt" to "x".toByteArray())) { out ->
            val link = ZipArchiveEntry("link")
            link.unixMode = 0b1010_000_111_111_111 // S_IFLNK | 0777
            out.putArchiveEntry(link)
            out.write("/etc/passwd".toByteArray())
            out.closeArchiveEntry()
        }

        listOf(tarBytes, zipBytes).forEachIndexed { index, bytes ->
            val streamed = File(root, "links$index")

            ArchiveExtractor.extractStream(liveStream(bytes), streamed, root, roomy(), root)

            val archive = File(root, "links$index.bin").apply { writeBytes(bytes) }
            val fromFile = File(root, "links-file$index")

            ArchiveExtractor.extractFile(archive, fromFile, root, roomy())

            listOf(streamed, fromFile).forEach { target ->
                assertTrue(File(target, "real.txt").isFile)
                assertFalse(Files.isSymbolicLink(File(target, "link").toPath()))
                assertFalse(exists(File(target, "hard")))
            }

            // Only a streamed zip has no way to know (the link flag lives in the central
            // directory): it becomes a plain file holding the link text, which is harmless.
            if (index == 0) {
                assertFalse(exists(File(streamed, "link")))
            }

            assertFalse(exists(File(fromFile, "link")))
        }
    }

    @Test
    fun `skips denied paths and keeps the rest`() {
        val target = File(root, "server").apply { mkdirs() }
        val bytes = zip(mapOf(".pano-agent/config.conf" to "a stolen token".toByteArray(), "world/level.dat" to "level".toByteArray()))

        ArchiveExtractor.extractStream(liveStream(bytes), target, target, roomy(), root)

        assertFalse(File(target, ".pano-agent/config.conf").exists())
        assertEquals("level", File(target, "world/level.dat").readText())
    }

    @Test
    fun `stops when the disk fills mid-extraction and removes what it wrote`() {
        val tarBytes = tar(linkedMapOf("first.bin" to ByteArray(1024 * 1024), "second.bin" to ByteArray(10 * 1024 * 1024)))
        val bytes = compress(tarBytes) { GzipCompressorOutputStream(it) }
        val target = File(root, "full")
        var reads = 0

        // Plenty of space for the first check, none after.
        val guard = DiskGuard(root, reserveBytes = 1024, usableSpace = { if (reads++ == 0) Long.MAX_VALUE / 2 else 10L })

        val failure = assertThrows(NotEnoughSpaceException::class.java) {
            ArchiveExtractor.extractStream(liveStream(bytes), target, root, guard, root)
        }

        assertEquals(ArchiveException.NOT_ENOUGH_SPACE, failure.code)
        assertEquals(10L, failure.free)
        assertNull(failure.required)
        assertFalse(File(target, "first.bin").exists(), "a complete file of this run is removed too")
        assertFalse(File(target, "second.bin").exists(), "the half-written file is removed")
        assertFalse(target.exists(), "the directory this run created is removed")
    }

    @Test
    fun `refuses up front when the space is not there`() {
        val guard = DiskGuard(File(root, "not/yet/there"), reserveBytes = 100, usableSpace = { 150L })

        guard.require(50)

        val failure = assertThrows(NotEnoughSpaceException::class.java) { guard.require(51) }

        assertEquals(151L, failure.required)
        assertEquals(150L, failure.free)
    }

    @Test
    fun `random bytes are not an archive`() {
        val failure = assertThrows(ArchiveException::class.java) {
            ArchiveExtractor.extractStream(liveStream(Random(1).nextBytes(100_000)), File(root, "r"), root, roomy(), root)
        }

        assertEquals(ArchiveException.UNSUPPORTED_ARCHIVE, failure.code)
    }

    @Test
    fun `a truncated archive is corrupt and leaves nothing behind`() {
        val full = compress(tar()) { GzipCompressorOutputStream(it) }
        val target = File(root, "cut")

        val failure = assertThrows(ArchiveException::class.java) {
            ArchiveExtractor.extractStream(liveStream(full.copyOf(full.size / 2)), target, root, roomy(), root)
        }

        assertEquals(ArchiveException.CORRUPT_ARCHIVE, failure.code)
        assertFalse(target.exists())
    }

    @Test
    fun `a dropped connection is reported as itself`() {
        val bytes = compress(tar()) { GzipCompressorOutputStream(it) }
        val target = File(root, "dropped")

        val failure = assertThrows(IOException::class.java) {
            ArchiveExtractor.extractStream(liveStream(bytes, failAfter = bytes.size / 2), target, root, roomy(), root)
        }

        assertEquals("connection reset", failure.message)
        assertFalse(target.exists())
    }

    // ---- detection --------------------------------------------------------------------------

    @Test
    fun `detects each format from its head without consuming it`() {
        val heads = LinkedHashMap<ArchiveFormat, ByteArray>(fixtures())
        val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) + ByteArray(64)
        val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) + ByteArray(64)

        heads[ArchiveFormat.GZ] = compress("just text".toByteArray()) { GzipCompressorOutputStream(it) }

        heads.forEach { (format, bytes) ->
            val input = BufferedInputStream(ByteArrayInputStream(bytes), 8192)

            assertEquals(format, ArchiveFormats.detect(input, null), format.id)
            assertArrayEquals(bytes, input.readBytes(), "the stream is untouched for ${format.id}")
        }

        assertEquals(ArchiveFormat.RAR, ArchiveFormats.detect(BufferedInputStream(ByteArrayInputStream(rar4)), null))
        assertEquals(ArchiveFormat.RAR, ArchiveFormats.detect(BufferedInputStream(ByteArrayInputStream(rar5)), null))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormats.detect(BufferedInputStream(ByteArrayInputStream(Random(3).nextBytes(4096))), "x.zip"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormats.detect(BufferedInputStream(ByteArrayInputStream("<html>404</html>".toByteArray())), "x.tar"))
        assertEquals(
            ArchiveFormat.UNKNOWN,
            ArchiveFormats.detect(BufferedInputStream(ByteArrayInputStream(compress(big) { BZip2CompressorOutputStream(it) }))),
            "a bzip2 of something that is not a tar"
        )
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormats.detect(File(root, "missing")))
    }

    @Test
    fun `detects a bzip2 tarball whose first block is large`() {
        // Random content defeats compression, so bzip2's first block is about 900 kB on the wire.
        val bytes = compress(tar(mapOf("noise.bin" to Random(9).nextBytes(2 * 1024 * 1024)))) { BZip2CompressorOutputStream(it) }

        assertEquals(ArchiveFormat.TAR_BZ2, ArchiveFormats.detect(BufferedInputStream(ByteArrayInputStream(bytes)), null))
    }

    // ---- RAR (external tool) ----------------------------------------------------------------

    @Test
    fun `no rar tool means RAR_UNAVAILABLE and no staging left behind`() {
        val archive = File(root, "x.rar").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)) }
        val target = File(root, "rar-none")

        listOf(emptyList(), listOf(RarExtractor.bsdtar("ghost", File(root, "does-not-exist.exe")))).forEach { tools ->
            val failure = assertThrows(ArchiveException::class.java) {
                ArchiveExtractor.extractRar(archive, target, root, roomy(), tools)
            }

            assertEquals(ArchiveException.RAR_UNAVAILABLE, failure.code)
        }

        assertTrue(root.listFiles()!!.none { it.name.startsWith(".pano-unrar-") })
    }

    private val windows = System.getProperty("os.name").startsWith("Windows")

    /** Windows' own libarchive tar; it reads tar too, which stands in for a RAR fixture here. */
    private fun systemTar(): File? = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\tar.exe").takeIf { windows && it.isFile }

    @Test
    fun `moves what the external tool unpacked into the target`() {
        val tool = systemTar()
        assumeTrue(tool != null, "needs Windows' bsdtar")

        val archive = File(root, "fixture.tar").apply { writeBytes(tar(tree + (".pano-agent/config.conf" to "token".toByteArray()))) }
        val target = File(root, "server").apply { mkdirs() }

        ArchiveExtractor.extractRar(archive, target, target, roomy(), listOf(RarExtractor.bsdtar("tar.exe", tool!!)))

        assertTree(target)
        assertFalse(File(target, ".pano-agent/config.conf").exists())
        assertTrue(File(target, "empty").isDirectory)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".pano-unrar-") })
    }

    @Test
    fun `a failing tool is a corrupt archive`() {
        val tool = systemTar()
        assumeTrue(tool != null, "needs Windows' bsdtar")

        val archive = File(root, "broken.rar").apply { writeBytes(Random(5).nextBytes(10_000)) }
        val target = File(root, "broken")

        val failure = assertThrows(ArchiveException::class.java) {
            ArchiveExtractor.extractRar(archive, target, root, roomy(), listOf(RarExtractor.bsdtar("tar.exe", tool!!)))
        }

        assertEquals(ArchiveException.CORRUPT_ARCHIVE, failure.code)
        assertTrue(failure.message!!.contains("exited with"), failure.message)
        assertFalse(target.exists())
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".pano-unrar-") })
    }

    /** A "tool" that just waits, to drive the watchdogs. */
    private fun hangingTool(): RarExtractor.Tool = if (windows) {
        RarExtractor.Tool("hang", File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\cmd.exe"), { _, _ ->
            listOf("/c", "ping", "-n", "60", "127.0.0.1")
        })
    } else {
        RarExtractor.Tool("hang", File("/bin/sh"), { _, _ -> listOf("-c", "sleep 60") })
    }

    @Test
    fun `a tool that stops making progress is killed`() {
        val tool = hangingTool()
        assumeTrue(tool.executable.isFile)

        val archive = File(root, "slow.rar").apply { writeBytes(ByteArray(16)) }
        val started = System.currentTimeMillis()

        val failure = assertThrows(ArchiveException::class.java) {
            ArchiveExtractor.extractRar(archive, File(root, "slow"), root, roomy(), listOf(tool), stallTimeoutMillis = 1500)
        }

        assertEquals(ArchiveException.CORRUPT_ARCHIVE, failure.code)
        assertTrue(System.currentTimeMillis() - started < 30_000, "killed well before the tool would have finished")
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".pano-unrar-") })
    }

    @Test
    fun `a tool is killed when the disk runs low`() {
        val tool = hangingTool()
        assumeTrue(tool.executable.isFile)

        val archive = File(root, "full.rar").apply { writeBytes(ByteArray(16)) }
        var reads = 0
        val guard = DiskGuard(root, reserveBytes = 1024, usableSpace = { if (reads++ < 1) Long.MAX_VALUE / 2 else 0L })
        val started = System.currentTimeMillis()

        assertThrows(NotEnoughSpaceException::class.java) {
            ArchiveExtractor.extractRar(archive, File(root, "fullrar"), root, guard, listOf(tool))
        }

        assertTrue(System.currentTimeMillis() - started < 30_000)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".pano-unrar-") })
    }
}
