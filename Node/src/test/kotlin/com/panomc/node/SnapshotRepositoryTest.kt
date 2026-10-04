package com.panomc.node

import com.panomc.node.backup.BackupScope
import com.panomc.node.backup.BackupScopeKind
import com.panomc.node.backup.ResolvedScope
import com.panomc.node.backup.snapshot.CorruptChunkException
import com.panomc.node.backup.snapshot.RepoBusyException
import com.panomc.node.backup.snapshot.SnapshotRepository
import com.panomc.node.files.BackupExcludeMatcher
import com.panomc.node.files.BackupService
import com.panomc.node.files.ZipTool
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class SnapshotRepositoryTest {
    @TempDir
    lateinit var temp: File

    private val server: File by lazy { File(temp, "server").apply { mkdirs() } }
    private val repoDir: File by lazy { File(temp, "backups/repo") }

    /**
     * A repository cut with small chunks, so a test file of a few hundred kilobytes is dozens of
     * chunks rather than one — which is also what checks that config.json is honoured.
     */
    private fun repository(): SnapshotRepository {
        repoDir.mkdirs()

        File(repoDir, SnapshotRepository.CONFIG_FILE)
            .writeText("""{"version":1,"chunker":"fastcdc","min":1024,"avg":4096,"max":16384}""")

        return SnapshotRepository(repoDir)
    }

    private fun random(size: Int, seed: Long): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun write(path: String, bytes: ByteArray, modified: Long = 1_700_000_000_000L) {
        val file = File(server, path)

        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        file.setLastModified(modified)
    }

    private fun seed() {
        write("server.properties", "level-name=world\n".toByteArray())
        write("world/level.dat", random(2_000, 1))
        write("world/region/r.0.0.mca", random(200_000, 2))
        write("world/region/r.0.1.mca", random(150_000, 3))
        write("plugins/Essentials/config.yml", "motd: hello\n".repeat(2_000).toByteArray())
        write("plugins/Pano/config.conf", "token = secret".toByteArray())
        write("logs/latest.log", "noise".toByteArray())
        write("server.json", "{}".toByteArray())
        File(server, "world/empty").mkdirs()
    }

    private fun all(): ResolvedScope =
        BackupScope.resolve(server, BackupScopeKind.ALL, null, BackupExcludeMatcher(null))

    private fun snapshot(repository: SnapshotRepository, id: String, createdAt: Long, scope: ResolvedScope = all()) =
        repository.create(server, id, id, createdAt, scope, emptyList(), BackupExcludeMatcher(null))

    /** Every file under [root], relative, with its bytes; directories as null. */
    private fun tree(root: File): Map<String, String?> = root.walkTopDown()
        .filter { it != root }
        .associate { file ->
            ZipTool.relativeName(root, file) to if (file.isFile) SnapshotRepository.sha256Hex(file.readBytes(), 0, file.readBytes().size) else null
        }
        .toSortedMap()

    private fun chunkFiles(): Set<String> = File(repoDir, SnapshotRepository.DATA_DIRECTORY).walkTopDown()
        .filter { it.isFile }
        .map { it.name }
        .toSet()

    @Test
    fun `the repository layout is the documented one`() {
        seed()

        val repository = repository()

        snapshot(repository, "first", 1)

        assertTrue(File(repoDir, "snapshots/first.json.gz").isFile)
        assertFalse(File(repoDir, SnapshotRepository.LOCK_FILE).exists(), "the lock is released")

        chunkFiles().forEach { name ->
            val file = File(repoDir, "data/${name.substring(0, 2)}/$name")
            val header = file.readBytes()[0]

            assertTrue(header == SnapshotRepository.HEADER_RAW || header == SnapshotRepository.HEADER_DEFLATE)
        }

        val json = GZIPInputStream(File(repoDir, "snapshots/first.json.gz").inputStream()).readBytes().decodeToString()

        assertTrue(json.contains("\"p\":\"world/region/r.0.0.mca\",\"t\":\"f\",\"s\":200000"), json.take(400))
        assertTrue(json.contains("{\"p\":\"world/empty\",\"t\":\"d\",\"m\":"), json.take(400))
    }

    @Test
    fun `denylisted and excluded files never reach a snapshot`() {
        seed()

        val manifest = snapshot(repository(), "first", 1).manifest
        val paths = manifest.entries.map { it.p }

        assertFalse(paths.contains("plugins/Pano/config.conf"))
        assertFalse(paths.contains("server.json"))
        assertFalse(paths.contains("logs/latest.log"))
        assertTrue(paths.contains("plugins/Essentials/config.yml"))
        assertEquals(paths.sorted(), paths)
    }

    @Test
    fun `compressible chunks are deflated and incompressible ones are stored raw`() {
        write("text.yml", "the same line again\n".repeat(20_000).toByteArray())
        write("random.bin", random(50_000, 9))

        val repository = repository()
        val manifest = snapshot(repository, "first", 1).manifest

        fun header(path: String): Byte {
            val id = manifest.entries.first { it.p == path }.c!!.first()

            return File(repoDir, "data/${id.substring(0, 2)}/$id").readBytes()[0]
        }

        assertEquals(SnapshotRepository.HEADER_DEFLATE, header("text.yml"))
        assertEquals(SnapshotRepository.HEADER_RAW, header("random.bin"))
    }

    @Test
    fun `a second snapshot stores only what changed, and both restore exactly`() {
        seed()

        val repository = repository()

        val first = snapshot(repository, "first", 1)
        val firstTree = tree(server)
        val chunksAfterFirst = chunkFiles()

        // One region file gains a few bytes in the middle; content-defined chunking keeps the rest.
        val region = File(server, "world/region/r.0.0.mca")
        val bytes = region.readBytes()

        region.writeBytes(bytes.copyOfRange(0, 100_000) + random(300, 42) + bytes.copyOfRange(100_000, bytes.size))
        region.setLastModified(1_800_000_000_000L)

        val second = snapshot(repository, "second", 2)
        val secondTree = tree(server)

        val newChunks = chunkFiles() - chunksAfterFirst

        assertEquals(newChunks.size, second.newChunks)
        assertTrue(second.newChunks in 1..3, "wrote ${second.newChunks} chunks for one small edit")
        assertTrue(second.storedBytes < 60_000, "stored ${second.storedBytes} bytes for one small edit")
        assertTrue(first.newChunks > 20)

        listOf("first" to firstTree, "second" to secondTree).forEach { (id, expected) ->
            val target = File(temp, "restore-$id").apply { mkdirs() }

            repository.restore(id, target)

            val restored = tree(target)

            // What a snapshot leaves out is not in the restore either.
            val kept = expected.filterKeys {
                !it.startsWith("logs") && it != "server.json" && it != "plugins/Pano/config.conf"
            }

            assertEquals(kept, restored, "restore of $id")
        }

        assertEquals(1_800_000_000_000L, File(temp, "restore-second/world/region/r.0.0.mca").lastModified())
        assertEquals(1_700_000_000_000L, File(temp, "restore-first/world/region/r.0.0.mca").lastModified())
    }

    @Test
    fun `an unchanged file reuses its parent's chunks without being read`() {
        seed()

        val repository = repository()

        val first = snapshot(repository, "first", 1).manifest

        // Same size and time, different content: only a snapshot that never opened the file
        // would still record the old chunks — which is exactly what parent reuse promises.
        val level = File(server, "world/level.dat")
        val modified = level.lastModified()

        level.writeBytes(random(2_000, 77))
        level.setLastModified(modified)

        val second = snapshot(repository, "second", 2)

        assertEquals(
            first.entries.first { it.p == "world/level.dat" }.c,
            second.manifest.entries.first { it.p == "world/level.dat" }.c
        )
        assertEquals(0, second.newChunks)
        assertEquals(0L, second.storedBytes)
    }

    @Test
    fun `a parent chunk that went missing makes the file be read again`() {
        seed()

        val repository = repository()
        val first = snapshot(repository, "first", 1).manifest

        val id = first.entries.first { it.p == "world/level.dat" }.c!!.single()

        File(repoDir, "data/${id.substring(0, 2)}/$id").delete()

        val second = snapshot(repository, "second", 2)

        assertEquals(1, second.newChunks)
        assertTrue(File(repoDir, "data/${id.substring(0, 2)}/$id").isFile)
    }

    @Test
    fun `deleting a snapshot collects only the chunks nothing else needs`() {
        seed()

        val repository = repository()

        snapshot(repository, "first", 1)

        File(server, "world/region/r.0.1.mca").writeBytes(random(150_000, 1234))

        val second = snapshot(repository, "second", 2)
        val secondChunks = second.manifest.chunkIds()

        // An orphan from a create that died halfway, and a stale temporary file.
        File(repoDir, "data/ab").mkdirs()
        File(repoDir, "data/ab/" + "ab".repeat(32)).writeBytes(byteArrayOf(0, 1, 2))
        File(repoDir, "data/ab/x.tmp").writeBytes(byteArrayOf(1))

        var removed = false
        val gc = repository.delete("first") { removed = true }

        assertTrue(removed)
        assertFalse(repository.has("first"))
        assertEquals(secondChunks, chunkFiles())
        assertTrue(gc.deletedChunks > 1)

        val target = File(temp, "restore").apply { mkdirs() }

        repository.restore("second", target)

        assertArrayEquals(File(server, "world/region/r.0.1.mca").readBytes(), File(target, "world/region/r.0.1.mca").readBytes())
    }

    @Test
    fun `a damaged manifest stops garbage collection instead of losing chunks`() {
        seed()

        val repository = repository()

        snapshot(repository, "first", 1)
        snapshot(repository, "second", 2)

        File(repoDir, "snapshots/second.json.gz").writeText("not gzip")

        val before = chunkFiles()
        val gc = repository.delete("first")

        assertTrue(gc.skipped)
        assertEquals(before, chunkFiles())
    }

    @Test
    fun `a corrupt chunk fails the restore before the target is touched`() {
        seed()

        val repository = repository()
        val manifest = snapshot(repository, "first", 1).manifest

        val id = manifest.entries.first { it.p == "world/region/r.0.1.mca" }.c!!.last()
        val chunk = File(repoDir, "data/${id.substring(0, 2)}/$id")
        val bytes = chunk.readBytes()

        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        chunk.writeBytes(bytes)

        val target = File(temp, "target").apply { mkdirs() }

        File(target, "world").mkdirs()
        File(target, "world/level.dat").writeText("current")

        var safetyTaken = false

        val failure = assertThrows(CorruptChunkException::class.java) {
            repository.restore("first", target, beforeWrite = { safetyTaken = true })
        }

        assertEquals("CORRUPT_CHUNK $id", failure.message)
        assertFalse(safetyTaken)
        assertEquals(listOf("world/level.dat"), tree(target).filterValues { it != null }.keys.toList())
        assertEquals("current", File(target, "world/level.dat").readText())
    }

    @Test
    fun `a missing chunk is reported by name`() {
        seed()

        val repository = repository()
        val manifest = snapshot(repository, "first", 1).manifest
        val id = manifest.entries.first { it.p == "world/level.dat" }.c!!.single()

        File(repoDir, "data/${id.substring(0, 2)}/$id").delete()

        val failure = assertThrows(CorruptChunkException::class.java) { repository.verify("first") }

        assertEquals(id, failure.chunkId)
    }

    @Test
    fun `a snapshot restore replaces world directories wholesale and overlays the rest`() {
        seed()

        val repository = repository()

        snapshot(repository, "first", 1)

        val target = File(temp, "live").apply { mkdirs() }

        // Generated after the backup: must not survive into the restored world.
        File(target, "world/region").mkdirs()
        File(target, "world/region/r.9.9.mca").writeText("new chunk")
        // Outside any world: a restore lays files over it and leaves the rest alone.
        File(target, "plugins/Other").mkdirs()
        File(target, "plugins/Other/data.yml").writeText("keep")
        // Credentials are never touched, not even by a world wipe.
        File(target, "plugins/Pano").mkdirs()
        File(target, "plugins/Pano/config.conf").writeText("live token")

        repository.restore("first", target)

        assertFalse(File(target, "world/region/r.9.9.mca").exists())
        assertTrue(File(target, "world/region/r.0.0.mca").isFile)
        assertTrue(File(target, "world/empty").isDirectory)
        assertEquals("keep", File(target, "plugins/Other/data.yml").readText())
        assertEquals("live token", File(target, "plugins/Pano/config.conf").readText())
    }

    @Test
    fun `a FULL restore replaces world directories wholesale too`() {
        seed()

        val archive = File(temp, "full.zip")

        ZipTool.archive(server, listOf(""), archive) { BackupExcludeMatcher(null).matches(it) }

        val target = File(temp, "live").apply { mkdirs() }

        File(target, "world/region").mkdirs()
        File(target, "world/region/r.9.9.mca").writeText("new chunk")
        File(target, "world_nether/DIM-1").mkdirs()
        File(target, "world_nether/DIM-1/r.0.0.mca").writeText("not in the backup, left alone")
        File(target, "notes.txt").writeText("keep")

        BackupService.restoreArchive(target, archive)

        assertFalse(File(target, "world/region/r.9.9.mca").exists())
        assertArrayEquals(File(server, "world/region/r.0.0.mca").readBytes(), File(target, "world/region/r.0.0.mca").readBytes())
        assertEquals("not in the backup, left alone", File(target, "world_nether/DIM-1/r.0.0.mca").readText())
        assertEquals("keep", File(target, "notes.txt").readText())
    }

    @Test
    fun `a WORLDS snapshot holds only the worlds`() {
        seed()

        val scope = BackupScope.resolve(server, BackupScopeKind.WORLDS, null, BackupExcludeMatcher(null))
        val manifest = snapshot(repository(), "worlds", 1, scope).manifest

        assertEquals(listOf("world"), manifest.roots)
        assertTrue(manifest.entries.all { it.p == "world" || it.p.startsWith("world/") })
        assertEquals(3L, manifest.fileCount)
    }

    @Test
    fun `a snapshot downloads as an ordinary zip`() {
        seed()

        val repository = repository()

        snapshot(repository, "first", 1)

        val output = ByteArrayOutputStream()

        repository.writeZip("first", output)

        val entries = linkedMapOf<String, ByteArray?>()

        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                entries[entry.name] = if (entry.isDirectory) null else zip.readBytes()
            }
        }

        assertArrayEquals(File(server, "world/region/r.0.0.mca").readBytes(), entries["world/region/r.0.0.mca"])
        assertTrue(entries.containsKey("world/empty/"))
        assertFalse(entries.containsKey("plugins/Pano/config.conf"))

        assertThrows(IllegalStateException::class.java) { repository.writeZip("first", ByteArrayOutputStream(), 1_000) }
    }

    @Test
    fun `a second operation on a busy repository is refused`() {
        seed()

        val repository = repository()
        val other = SnapshotRepository(repoDir)

        var inner: Throwable? = null

        snapshot(repository, "first", 1)

        repository.delete("nothing") {
            inner = runCatching { snapshot(other, "second", 2) }.exceptionOrNull()
        }

        assertTrue(inner is RepoBusyException, "got $inner")
        assertNull(other.manifest("second"))

        // And free again afterwards.
        snapshot(other, "second", 2)

        assertTrue(other.has("second"))
    }

    @Test
    fun `the default config is written on first use`() {
        seed()

        val repository = SnapshotRepository(repoDir)

        snapshot(repository, "first", 1)

        assertEquals(
            """{"version":1,"chunker":"fastcdc","min":262144,"avg":1048576,"max":4194304}""",
            File(repoDir, SnapshotRepository.CONFIG_FILE).readText()
        )
        assertTrue(Files.exists(File(repoDir, "snapshots/first.json.gz").toPath()))
    }
}
