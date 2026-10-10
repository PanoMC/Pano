package com.panomc.snapshot

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

/** The pack file format (`archive-format.md` §7.1.1): writer, index round trip and every rejection of the reader. */
class SnapshotPackTest {
    @TempDir
    lateinit var tmp: File

    private val master = ByteArray(32) { (it * 3 + 5).toByte() }
    private val aesKey = SnapshotSeal.deriveKey(master)
    private val opener = SnapshotSeal.opener(mapOf("k1" to master))
    private val packId = "0123456789abcdef0123456789abcdef"

    private fun chunk(seed: Int, size: Int = 200 + seed * 37): Pair<String, ByteArray> {
        val raw = Random(seed).nextBytes(size)
        val id = ChunkCodec.sha256Hex(raw)

        return id to SnapshotSeal.seal(aesKey, "k1", SnapshotFormat.chunkPath(id), ChunkCodec().use { it.encode(raw) })
    }

    private fun u32(value: Long): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()

    private fun idBytes(id: String) = ByteArray(32) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A pack file put together by hand: [header], [blobs], [index] sealed for [sealedFor], the trailer. */
    private fun pack(blobs: List<ByteArray>, index: ByteArray, header: ByteArray = "PPACK".toByteArray() + 1, sealedFor: String = "packs/$packId", trailer: Long? = null): File {
        val out = ByteArrayOutputStream()
        val sealed = SnapshotSeal.seal(aesKey, "k1", sealedFor, index)

        out.write(header)
        blobs.forEach(out::write)
        out.write(sealed)
        out.write(u32(trailer ?: sealed.size.toLong()))

        return File(tmp, "pack-${System.nanoTime()}").apply { writeBytes(out.toByteArray()) }
    }

    /** The index plaintext of `(id, offset, length)` rows, with [count] as its count field. */
    private fun index(rows: List<Triple<String, Long, Long>>, count: Long = rows.size.toLong()): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(u32(count))
        rows.forEach { (id, offset, length) ->
            out.write(idBytes(id))
            out.write(u32(offset))
            out.write(u32(length))
        }

        return out.toByteArray()
    }

    private fun rejected(file: File, expect: String = "SNAPSHOT_PACK_INVALID", id: String = packId): String {
        val error = assertThrows<SnapshotIntegrityException> { SnapshotPack.readIndex(file, id, opener) }

        assertTrue(error.message!!.contains(expect), error.message)

        return error.message!!
    }

    @Test
    fun `a written pack has the documented layout and its index round-trips`() {
        val chunks = (1..5).map { chunk(it) }
        val file = File(tmp, "pack")
        val written = PackWriter(file).use { writer ->
            chunks.forEach { (id, sealed) -> assertTrue(writer.add(id, sealed)) }

            // A chunk the pack already holds is not written twice.
            assertFalse(writer.add(chunks[2].first, chunks[2].second))
            assertEquals(5, writer.count)
            assertEquals(chunks.sumOf { it.second.size.toLong() }, writer.chunkBytes)

            writer.finish(aesKey, "k1", packId)
        }
        val bytes = file.readBytes()

        // "PPACK" | 0x01 | blob* | sealedIndex | u32 BE len(sealedIndex)
        assertArrayEquals("PPACK".toByteArray() + 1, bytes.copyOfRange(0, 6))

        var offset = 6

        chunks.forEachIndexed { i, (id, sealed) ->
            assertEquals(PackEntry(id, offset.toLong(), sealed.size), written[i])
            assertArrayEquals(sealed, bytes.copyOfRange(offset, offset + sealed.size), "blob $i is the sealed chunk, byte for byte")
            offset += sealed.size
        }

        val sealedLength = ByteBuffer.wrap(bytes, bytes.size - 4, 4).getInt()

        assertEquals(bytes.size - 4 - sealedLength, offset, "the sealed index follows the last blob")

        // index = u32 BE count | count × (32 bytes id | u32 BE offset | u32 BE length), sealed for packs/<packId>.
        val plain = SnapshotSeal.open(opener, "packs/$packId", bytes.copyOfRange(offset, bytes.size - 4))

        assertArrayEquals(index(written.map { Triple(it.id, it.offset, it.length.toLong()) }), plain)
        assertArrayEquals(plain, SnapshotPack.encodeIndex(written))
        assertEquals(written, SnapshotPack.decodeIndex(packId, plain, offset.toLong()))
        assertEquals(written, SnapshotPack.readIndex(file, packId, opener))

        // A chunk blob opens with its chunk name, whichever pack it sits in.
        written.forEach { entry ->
            val sealed = bytes.copyOfRange(entry.offset.toInt(), entry.offset.toInt() + entry.length)

            assertEquals(entry.id, ChunkCodec.sha256Hex(ChunkCodec.decode(entry.id, SnapshotSeal.open(opener, SnapshotFormat.chunkPath(entry.id), sealed))))
        }
    }

    @Test
    fun `a reader refuses a foreign header, a bad trailer and an index sealed for another pack or key`() {
        val (id, sealed) = chunk(1)
        val rows = listOf(Triple(id, 6L, sealed.size.toLong()))

        assertEquals(listOf(PackEntry(id, 6, sealed.size)), SnapshotPack.readIndex(pack(listOf(sealed), index(rows)), packId, opener))

        rejected(pack(listOf(sealed), index(rows), header = "PPACX".toByteArray() + 1), "not a pack file")
        rejected(pack(listOf(sealed), index(rows), header = "PPACK".toByteArray() + 2), "not a pack file")
        rejected(pack(listOf(sealed), index(rows), trailer = 0), "index length")
        rejected(pack(listOf(sealed), index(rows), trailer = 1L shl 31), "index length")
        rejected(pack(listOf(sealed), index(rows), trailer = 100_000), "index length")
        rejected(File(tmp, "short").apply { writeBytes("PPACK".toByteArray() + 1) }, "cut short")

        // The trailer one byte off: the sealed index no longer opens.
        rejected(pack(listOf(sealed), index(rows), trailer = SnapshotSeal.overhead("k1") + index(rows).size - 1L), "SNAPSHOT_OBJECT")

        // Sealed for another pack id, read as another pack id, an unknown key.
        rejected(pack(listOf(sealed), index(rows), sealedFor = "packs/" + "f".repeat(32)), "SNAPSHOT_OBJECT_TAMPERED")
        rejected(pack(listOf(sealed), index(rows)), "SNAPSHOT_OBJECT_TAMPERED", id = "f".repeat(32))

        val foreign = assertThrows<SnapshotIntegrityException> { SnapshotPack.readIndex(pack(listOf(sealed), index(rows)), packId, SnapshotSeal.opener(mapOf("k2" to master))) }

        assertTrue(foreign.message!!.startsWith("SNAPSHOT_KEY_UNKNOWN"))

        // More than 1 GiB (a sparse file).
        val huge = File(tmp, "huge")

        RandomAccessFile(huge, "rw").use { it.setLength(SnapshotPack.MAX_PACK_BYTES + 1) }
        rejected(huge, "larger than")
        huge.delete()
    }

    @Test
    fun `a reader refuses an index that is not 1 to 1000000 unique contiguous entries covering the blobs`() {
        val (a, sealedA) = chunk(1)
        val (b, sealedB) = chunk(2)
        val blobs = listOf(sealedA, sealedB)
        val la = sealedA.size.toLong()
        val lb = sealedB.size.toLong()

        fun refused(rows: List<Triple<String, Long, Long>>, expect: String, count: Long = rows.size.toLong()) = rejected(pack(blobs, index(rows, count)), expect)

        assertEquals(listOf(PackEntry(a, 6, la.toInt()), PackEntry(b, 6 + la, lb.toInt())), SnapshotPack.readIndex(pack(blobs, index(listOf(Triple(a, 6L, la), Triple(b, 6 + la, lb)))), packId, opener))

        // Count: none, more than a million, not what the index length says.
        refused(emptyList(), "holds 0 chunks")
        refused(emptyList(), "holds 1000001 chunks", count = 1_000_001)
        refused(emptyList(), "holds 4294967295 chunks", count = 0xFFFFFFFFL)
        refused(listOf(Triple(a, 6L, la), Triple(b, 6 + la, lb)), "does not match its count", count = 1)
        refused(listOf(Triple(a, 6L, la)), "does not match its count", count = 2)
        rejected(pack(blobs, index(listOf(Triple(a, 6L, la), Triple(b, 6 + la, lb))) + 0), "does not match its count")
        rejected(pack(blobs, byteArrayOf(0, 0)), "cut short")

        // Offsets: the first blob starts at 6, every next one where the previous ends, the last ends at the sealed index.
        refused(listOf(Triple(a, 0L, la + 6), Triple(b, 6 + la, lb)), "not contiguous")
        refused(listOf(Triple(a, 7L, la - 1), Triple(b, 6 + la, lb)), "not contiguous")
        refused(listOf(Triple(a, 6L, la), Triple(b, 7 + la, lb - 1)), "not contiguous")
        refused(listOf(Triple(a, 6L, la), Triple(b, 5 + la, lb + 1)), "not contiguous")
        refused(listOf(Triple(b, 6 + la, lb), Triple(a, 6L, la)), "not contiguous")
        refused(listOf(Triple(a, 6L, la), Triple(b, 6 + la, lb + 1)), "not contiguous")
        refused(listOf(Triple(a, 6L, la), Triple(b, 6 + la, 0xFFFFFFFFL)), "not contiguous")
        refused(listOf(Triple(a, 6L, 0L), Triple(b, 6L, la + lb)), "not contiguous")
        refused(listOf(Triple(a, 6L, la), Triple(b, 6 + la, lb - 1)), "does not cover the pack")
        refused(listOf(Triple(a, 6L, la)), "does not cover the pack")

        // Ids are unique inside a pack.
        refused(listOf(Triple(a, 6L, la), Triple(a, 6 + la, lb)), "listed twice")
    }

    @Test
    fun `the index encoder only takes what a reader accepts`() {
        val id = "ab".repeat(32)

        assertThrows<IllegalArgumentException> { SnapshotPack.encodeIndex(emptyList()) }
        assertThrows<IllegalArgumentException> { SnapshotPack.encodeIndex(listOf(PackEntry("xyz", 6, 10))) }
        assertThrows<IllegalArgumentException> { SnapshotPack.encodeIndex(listOf(PackEntry(id, 5, 10))) }
        assertThrows<IllegalArgumentException> { SnapshotPack.encodeIndex(listOf(PackEntry(id, 6, 0))) }
        assertThrows<IllegalArgumentException> { SnapshotPack.packPath("../x") }
        assertTrue(SnapshotPack.isPackId(packId))
        assertFalse(SnapshotPack.isPackId(packId.uppercase()) || SnapshotPack.isPackId(packId + "0") || SnapshotPack.isPackId(packId.dropLast(1)))
    }

    @Test
    fun `a pack of the golden repository's chunks is read back chunk by chunk and unseals with the derived key`() {
        val base = File(javaClass.getResource("/snapshot/gear.txt")!!.toURI()).parentFile
        val manifest = SnapshotFormat.decodeManifest(File(base, "repo/snapshots/snap-golden.json.gz").readBytes())
        val ids = manifest.chunkIds().toList()
        val file = File(tmp, "golden.pack")
        val stored = ids.associateWith { File(base, "repo/${SnapshotFormat.chunkPath(it)}").readBytes() }

        // Fixed nonces: the sealed chunks and the whole index are reproducible.
        val written = PackWriter(file).use { writer ->
            ids.forEachIndexed { i, id ->
                assertTrue(writer.add(id, SnapshotSeal.seal(aesKey, "k1", SnapshotFormat.chunkPath(id), stored.getValue(id), ByteArray(12) { (i + it).toByte() })))
            }

            writer.finish(aesKey, "k1", packId)
        }

        assertEquals(ids, written.map { it.id })

        PackReader(file, packId, opener).use { reader ->
            assertEquals(written, reader.entries)
            assertFalse(reader.has("0".repeat(64)))
            assertEquals(null, reader.sealedChunk("0".repeat(64)))

            ids.forEach { id ->
                val raw = reader.readChunk(id)

                assertEquals(id, ChunkCodec.sha256Hex(raw))
                assertArrayEquals(raw, ChunkCodec.decode(id, stored.getValue(id)))
            }

            assertThrows<CorruptChunkException> { reader.readChunk("0".repeat(64)) }

            // A blob moved to another chunk name does not open.
            assertThrows<SnapshotIntegrityException> { SnapshotSeal.openChunk(opener, ids[1], reader.sealedChunk(ids[0])!!) }
        }

        // The file as a whole is a pack for its own id only.
        assertThrows<SnapshotIntegrityException> { PackReader(file, "f".repeat(32), opener) }
        assertThrows<SnapshotIntegrityException> { PackReader(file, packId, SnapshotSeal.opener(mapOf("k1" to ByteArray(32)))) }
    }

    @Test
    fun `a pack stops fitting before it passes one GiB or a million chunks`() {
        PackWriter(File(tmp, "fits.pack")).use { writer ->
            assertTrue(writer.fits(1))
            assertTrue(writer.fits(512 * 1024 * 1024))
            assertFalse(writer.fits(SnapshotPack.MAX_PACK_BYTES.toInt()))
        }
    }
}
