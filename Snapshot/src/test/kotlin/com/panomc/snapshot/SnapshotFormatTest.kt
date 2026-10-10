package com.panomc.snapshot

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.Deflater

/**
 * Byte compatibility with pano-node's snapshot format (`archive-format.md` §7): every vector in
 * `resources/snapshot/` was produced by the Node sources themselves (see `generator/GENERATED.txt`),
 * the sealed-object vectors by an independent JCA implementation.
 */
class SnapshotFormatTest {
    private val base = File(javaClass.getResource("/snapshot/gear.txt")!!.toURI()).parentFile
    private fun text(name: String) = File(base, name).readText()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Same stream as the generator: SplitMix64 draws, little-endian, truncated. */
    private fun splitMix(seed: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var state = seed
        var i = 0

        while (i < length) {
            state += 0x9E3779B97F4A7C15uL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            z = z xor (z ushr 31)
            for (b in 0 until 8) if (i < length) out[i++] = (z ushr (8 * b)).toByte()
        }

        return out
    }

    private fun cuts(chunker: FastCdc, input: InputStream): String {
        val sb = StringBuilder()

        chunker.split(input) { buffer, offset, length ->
            sb.append(length).append(' ').append(ChunkCodec.sha256Hex(buffer, offset, length)).append('\n')
        }

        return sb.toString()
    }

    /** Hands out at most [step] bytes per read, to prove cuts do not depend on read sizes. */
    private class Trickle(bytes: ByteArray, private val step: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, step))
    }

    private val config by lazy { SnapshotFormat.parseConfig(File(base, "repo/config.json").readBytes()) }
    private val manifestGz by lazy { File(base, "repo/snapshots/snap-golden.json.gz").readBytes() }

    @Test
    fun `gear table matches pano-node`() {
        assertEquals(text("gear.txt"), FastCdc.GEAR.joinToString("\n", postfix = "\n") { "%016x".format(it) })
    }

    @Test
    fun `cut points and chunk ids match pano-node at default and small parameters`() {
        val small = FastCdc(64, 256, 1024)
        val big = splitMix(1, 12 * 1024 * 1024 + 12345)

        assertEquals(text("cuts-default.txt"), cuts(FastCdc(), ByteArrayInputStream(big)))
        assertEquals(text("cuts-default.txt"), cuts(FastCdc(), Trickle(big, 777_777)))

        val smallData = splitMix(2, 64 * 1024 + 77)

        assertEquals(text("cuts-small.txt"), cuts(small, ByteArrayInputStream(smallData)))
        assertEquals(text("cuts-small.txt"), cuts(small, Trickle(smallData, 100)))

        val mixed = splitMix(3, 3000) + ByteArray(5000) + splitMix(4, 3000)

        assertEquals(text("cuts-mixed-small.txt"), cuts(small, ByteArrayInputStream(mixed)))
        assertEquals("", cuts(small, ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `a version 2 config carries the pack size and is validated`() {
        val v2 = """{"version":2,"chunker":"fastcdc","min":262144,"avg":1048576,"max":4194304,"pack":33554432}"""
        val parsed = SnapshotFormat.parseConfig(v2.toByteArray())

        assertEquals(SnapshotRepoConfig(version = 2, pack = 33554432L), parsed.validate())
        assertTrue(parsed.isPackRepository)
        assertEquals(v2, String(SnapshotFormat.configJson(parsed)))
        assertEquals(1048576L, parsed.copy(pack = 1048576).validate().pack)
        assertEquals(268435456L, parsed.copy(pack = 268435456).validate().pack)

        assertThrows<IllegalArgumentException> { parsed.copy(version = 1).validate() }
        assertThrows<IllegalArgumentException> { parsed.copy(version = 3).validate() }
        assertThrows<IllegalArgumentException> { parsed.copy(pack = 1048575).validate() }
        assertThrows<IllegalArgumentException> { parsed.copy(pack = 268435457).validate() }
        assertThrows<IllegalArgumentException> { parsed.copy(pack = null).validate() }
        assertThrows<IllegalArgumentException> { parsed.copy(chunker = "rabin").validate() }
        assertThrows<IllegalArgumentException> { parsed.copy(avg = 300).validate() }
    }

    @Test
    fun `the control plane's golden manifest is the same file and reads the same`() {
        val control = File(base, "control-plane-golden-manifest.json.gz").readBytes()

        assertArrayEquals(manifestGz, control)
        assertEquals("snap-golden", SnapshotFormat.decodeManifest(control).id)
        assertThrows<RuntimeException> { SnapshotFormat.decodeManifest(control, maxJsonBytes = 100) }
    }

    @Test
    fun `repository config round-trips to the same bytes`() {
        val golden = File(base, "repo/config.json").readBytes()

        assertEquals(SnapshotRepoConfig(min = 64, avg = 256, max = 1024), config.validate())
        assertArrayEquals(golden, SnapshotFormat.configJson(config))
        assertEquals(
            """{"version":1,"chunker":"fastcdc","min":262144,"avg":1048576,"max":4194304}""",
            String(SnapshotFormat.configJson(SnapshotRepoConfig()))
        )
        assertThrows<IllegalArgumentException> { SnapshotRepoConfig(chunker = "rabin").validate() }
    }

    @Test
    fun `chunking the tree gives the manifest's chunk lists and the chunk files decode`() {
        val manifest = SnapshotFormat.decodeManifest(manifestGz)
        val chunker = FastCdc.of(config)
        val codec = ChunkCodec()
        val files = manifest.entries.filter { it.isFile }
        var deflated = 0

        assertEquals(6, files.size)

        files.forEach { entry ->
            val bytes = File(base, "tree/${entry.p}").readBytes()
            val ids = ArrayList<String>()

            chunker.split(ByteArrayInputStream(bytes)) { buffer, offset, length ->
                val id = ChunkCodec.sha256Hex(buffer, offset, length)
                val golden = File(base, "repo/${SnapshotFormat.chunkPath(id)}").readBytes()
                val ours = codec.encode(buffer, offset, length)
                val raw = buffer.copyOfRange(offset, offset + length)

                ids.add(id)
                assertEquals(golden[0], ours[0], "header of $id")
                if (golden[0] == ChunkCodec.HEADER_RAW) assertArrayEquals(golden, ours) else deflated++
                assertArrayEquals(raw, ChunkCodec.decode(id, golden))
                assertArrayEquals(raw, ChunkCodec.decode(id, ours))
            }

            assertEquals(entry.c, ids, entry.p)
            assertEquals(entry.s, bytes.size.toLong(), entry.p)
        }

        assertEquals(51, manifest.chunkIds().size)
        assertEquals(51, File(base, "repo/data").walkTopDown().count { it.isFile })
        assert(deflated > 0)
        codec.close()
    }

    @Test
    fun `manifest JSON is byte-identical to pano-node's Gson output`() {
        val golden = File(base, "manifest.json").readBytes()
        val parsed = SnapshotFormat.decodeManifest(manifestGz)

        assertArrayEquals(golden, SnapshotFormat.manifestJson(parsed))

        // Rebuilt from scratch in shuffled order: sorted() restores the canonical order.
        val rebuilt = SnapshotManifest(
            id = "snap-golden", name = "DAILY", createdAt = 1_790_000_000_000L, scope = SnapshotFormat.SCOPE_PANO_INSTANCE,
            exclude = listOf("app/file-uploads/cache", "app/file-uploads/temp", "app/file-uploads/transfer", "app/themes/**/.pano-license.jwt"),
            roots = listOf("app", "db", "meta"),
            entries = parsed.entries.map { SnapshotEntry(it.p, it.t, it.s, it.m, it.c?.toList()) }.reversed()
        ).sorted()

        assertArrayEquals(golden, SnapshotFormat.manifestJson(rebuilt))
        assert(String(golden).contains("\"p\":\"app/themes/a&b=<c> ş.txt\""))
        assert(String(golden).contains("{\"p\":\"app\",\"t\":\"d\",\"m\":1790000009000}"))
        assert(String(golden).contains("\"s\":0,\"m\":1790000003000,\"c\":[]"))

        val encoded = SnapshotFormat.encodeManifest(rebuilt)

        // Byte 9 of the gzip header is the OS field, which the JDK sets per release (0 before 16, 255 after).
        assertArrayEquals(manifestGz.copyOfRange(0, 9), encoded.copyOfRange(0, 9))
        assertArrayEquals(manifestGz.copyOfRange(10, manifestGz.size), encoded.copyOfRange(10, encoded.size))
        assertEquals(rebuilt, SnapshotFormat.decodeManifest(encoded))
        assertEquals(6, rebuilt.fileCount)
        assertEquals(parsed.entries.sumOf { it.s ?: 0 }, rebuilt.logicalBytes)
    }

    @Test
    fun `chunk decoding rejects foreign or corrupt payloads`() {
        val raw = "hello".toByteArray()
        val id = ChunkCodec.sha256Hex(raw)
        val codec = ChunkCodec()

        assertEquals(ChunkCodec.HEADER_RAW, codec.encode(raw)[0])
        assertEquals(ChunkCodec.HEADER_DEFLATE, codec.encode(ByteArray(4096))[0])

        fun fails(block: () -> Unit) {
            val error = assertThrows<CorruptChunkException> { block() }

            assertTrue(error.message!!.startsWith("CORRUPT_CHUNK "))
        }

        fails { ChunkCodec.decode(id, byteArrayOf(0x02) + raw) }
        fails { ChunkCodec.decode(id, ByteArray(0)) }
        fails { ChunkCodec.decode(id, byteArrayOf(0x00) + "hellO".toByteArray()) }
        fails { ChunkCodec.decode("ABC", byteArrayOf(0x00) + raw) }
        fails { ChunkCodec.decode(id, byteArrayOf(0x01, 0x78, 0x00, 0x13)) }

        // A deflate bomb past the 64 MiB inflate cap is refused before hashing.
        val deflater = Deflater(9)
        val bomb = java.io.ByteArrayOutputStream()
        val zeros = ByteArray(1024 * 1024)
        val buffer = ByteArray(64 * 1024)

        repeat(65) {
            deflater.setInput(zeros)
            while (!deflater.needsInput()) bomb.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.finish()
        while (!deflater.finished()) bomb.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        fails { ChunkCodec.decode("0".repeat(64), byteArrayOf(0x01) + bomb.toByteArray()) }
        codec.close()
    }

    @Test
    fun `sealed objects match the vectors and bind key id, path and content`() {
        val master = ByteArray(32) { it.toByte() }

        assertEquals(text("derived-key.txt").trim(), hex(SnapshotSeal.deriveKey(master)))

        val lines = text("seal.txt").lines().filter { it.isNotBlank() && !it.startsWith("#") }

        assertEquals(4, lines.size)

        lines.forEach { line ->
            val (keyHex, keyId, path, nonceHex, plainHex, sealedHex) = line.split(' ')
            val plain = if (plainHex == "-") ByteArray(0) else unhex(plainHex)
            val sealed = unhex(sealedHex)
            val aes = SnapshotSeal.deriveKey(unhex(keyHex))
            val opener = SnapshotSeal.opener(mapOf(keyId to unhex(keyHex)))

            assertEquals(sealedHex, hex(SnapshotSeal.seal(aes, keyId, path, plain, unhex(nonceHex))))
            assertEquals(keyId, SnapshotSeal.keyIdOf(sealed))
            assertArrayEquals(plain, SnapshotSeal.open(opener, path, sealed))
            assertEquals(plain.size + SnapshotSeal.overhead(keyId), sealed.size)

            fun fails(block: () -> Unit) =
                assertThrows<SnapshotIntegrityException> { block() }

            fails { SnapshotSeal.open(opener, "$path.x", sealed) }
            fails { SnapshotSeal.open(opener, path, sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }) }
            fails { SnapshotSeal.open(opener, path, sealed.copyOf().also { it[7 + keyId.length + 3] = (it[7 + keyId.length + 3].toInt() xor 1).toByte() }) }
            fails { SnapshotSeal.open({ null }, path, sealed) }
            fails { SnapshotSeal.open(SnapshotSeal.opener(mapOf(keyId to ByteArray(32))), path, sealed) }
        }

        // Random nonces: two seals of the same object differ, both open.
        val aes = SnapshotSeal.deriveKey(master)
        val a = SnapshotSeal.seal(aes, "k1", "config.json", "x".toByteArray())
        val b = SnapshotSeal.seal(aes, "k1", "config.json", "x".toByteArray())

        assert(!a.contentEquals(b))
        assertArrayEquals("x".toByteArray(), SnapshotSeal.open({ aes }, "config.json", b))
        assertNull(SnapshotSeal.keyIdOf("PANOARC\u0001".toByteArray()))
    }

    @Test
    fun `layout paths follow the Node repository`() {
        val id = "ab" + "0".repeat(62)

        assertEquals("data/ab/$id", SnapshotFormat.chunkPath(id))
        assertEquals("snapshots/snap-1.json.gz", SnapshotFormat.manifestPath("snap-1"))
        assertThrows<IllegalArgumentException> { SnapshotFormat.manifestPath("../x") }
        assertThrows<IllegalArgumentException> { SnapshotFormat.chunkPath("AB") }
    }

    private operator fun <T> List<T>.component6() = this[5]
}
